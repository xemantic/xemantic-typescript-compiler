/*
 * TypeScript to JavaScript transpiler in Kotlin multiplatform
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.typescript.compiler

class Parser(
    private val source: String,
    private val fileName: String,
    forceJsx: Boolean = false,
    topLevelAwait: Boolean = false,
    private val needsJsxFlag: Boolean = false,
    /**
     * True when `noImplicitAny` (or `strict`) is enabled. Drives parser-side
     * implicit-any diagnostics like TS7039 (mapped type without value type).
     */
    private val noImplicitAny: Boolean = false,
) {

    private val scanner = Scanner(source)
    private var token: SyntaxKind = SyntaxKind.Unknown
    private var prevToken: SyntaxKind = SyntaxKind.Unknown
    private val diagnostics = mutableListOf<Diagnostic>()
    private var inAsyncContext = topLevelAwait
    private var disallowIn = false
    private var classBodyDepth = 0
    private var inTypeArgsDepth = 0
    private var inTupleTypeDepth = 0
    private var jsxElementDepth = 0

    /** Stack of opening token positions for related-info on missing close tokens. */
    private val openTokenStack = mutableListOf<Int>()
    // B18.1: set true by parseInterfaceMembers when it bails out on a
    // top-level var/let/const inside an interface body — the caller skips
    // its parseExpected(CloseBrace) so TS1131 (already emitted) isn't
    // followed by a redundant TS1005.
    private var interfaceMembersBailedOnKeyword = false

    // True while parsing the body of a type literal (`{ ... }` in a type position) as opposed
    // to an interface body. Used to pick TS1247 ("A type literal property cannot have an
    // initializer.") vs TS1246 ("An interface property cannot have an initializer.").
    private var inTypeLiteralForErrorWording = false

    /** True if the file uses JSX syntax (`.tsx` or `.jsx`, or forcibly enabled). */
    private val isJsxFile = forceJsx || fileName.endsWith(".tsx") || fileName.endsWith(".jsx")

    /** True if the file is JS-like (`.js`/`.jsx`/`.cjs`/`.mjs`) — gates JSDoc `@type` interpretation. */
    private val isJsLikeFile = fileName.endsWith(".js") || fileName.endsWith(".jsx") ||
        fileName.endsWith(".cjs") || fileName.endsWith(".mjs")

    /** Pre-computed line start positions for fast line/character lookup. */
    private val lineStarts: IntArray = computeLineStarts(source)

    fun parse(): SourceFile {
        // TS1490: Detect binary files via C0 control char heuristic — emit TS1490
        // at (1,1) and short-circuit parsing so we don't flood with TS1127. This
        // matches TypeScript's behavior for `corrupted_ts`. Tests like
        // `TransportStream_ts` that mix binary content with parseable tokens
        // need additional per-byte TS1127 emission and span-merging which we
        // don't yet implement — they remain failing.
        if (isBinaryFile(source)) {
            reportError("File appears to be binary.", code = 1490,
                overrideStart = 0, overrideLength = 0)
            return SourceFile(
                fileName = fileName,
                statements = emptyList(),
                text = source,
                end = source.length,
            )
        }
        // 16.0: Check triple-slash reference path directives for self-reference (TS1006).
        checkTripleSlashSelfReference()
        nextToken()
        val statements = parseStatements(topLevel = true)
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

    /**
     * Heuristic: a file appears to be binary if it contains 3 or more C0 control
     * characters (0x00-0x08, 0x0B, 0x0C, 0x0E-0x1F) within the first 512 bytes.
     * Single occurrences may legitimately appear inside string literals (e.g.
     * embedded SHIFT-OUT in `unicodeStringLiteral_ts`); 3+ usually indicate
     * binary content (corrupted_ts has 0x1F/0x03/0x03/0x19/0x1F).
     */
    private fun isBinaryFile(text: String): Boolean {
        val limit = minOf(text.length, 512)
        var count = 0
        for (i in 0 until limit) {
            val c = text[i].code
            if (c < 0x20 && c != 0x09 && c != 0x0A && c != 0x0D) {
                count++
                if (count >= 3) return true
            }
        }
        return false
    }

    // ── Infrastructure ──────────────────────────────────────────────────────

    private fun nextToken(): SyntaxKind {
        prevToken = token
        token = scanner.scan()
        return token
    }

    private fun parseExpected(kind: SyntaxKind, eofRelated: Boolean = true): Boolean {
        if (token == kind) {
            // Track opening tokens for related-info on missing close
            if (kind == SyntaxKind.OpenBrace || kind == SyntaxKind.OpenBracket || kind == SyntaxKind.OpenParen) {
                openTokenStack.add(scanner.getTokenPos())
            } else if (kind == SyntaxKind.CloseBrace || kind == SyntaxKind.CloseBracket || kind == SyntaxKind.CloseParen) {
                if (openTokenStack.isNotEmpty()) openTokenStack.removeAt(openTokenStack.lastIndex)
            }
            nextToken(); return true
        }
        // When missing a close token at EOF, add related info pointing to the opening token
        // (eofRelated=false suppresses the TS1007 related info — TypeScript only emits it
        // from parseExpectedMatchingBrackets call sites, which exclude parameter lists).
        if ((kind == SyntaxKind.CloseBrace || kind == SyntaxKind.CloseBracket || kind == SyntaxKind.CloseParen)
            && openTokenStack.isNotEmpty() && token == SyntaxKind.EndOfFile) {
            if (!eofRelated) {
                openTokenStack.removeAt(openTokenStack.lastIndex)
                reportError("'${tokenToString(kind)}' expected.", code = 1005)
                return false
            }
            val openPos = openTokenStack.removeAt(openTokenStack.lastIndex)
            val openToken = when (kind) {
                SyntaxKind.CloseBrace -> "{"
                SyntaxKind.CloseBracket -> "["
                else -> "("
            }
            val closeToken = tokenToString(kind)
            reportErrorWithRelatedInfo(
                "'$closeToken' expected.", 1005,
                "The parser expected to find a '$closeToken' to match the '$openToken' token here.",
                1007, openPos
            )
        } else if (token == SyntaxKind.Unknown) {
            // Unknown token = invalid character (e.g. ¬, ©, or other non-ASCII non-identifier char).
            // Emit TS1127 "Invalid character." rather than TS1005 "'{expected}' expected.".
            reportError("Invalid character.", code = 1127)
        } else {
            reportError("'${tokenToString(kind)}' expected.", code = 1005)
        }
        return false
    }

    /**
     * Like [parseExpected] for closing tokens, but provides TS1007 related info
     * pointing to [openPos] when the closing token is missing (not just at EOF).
     */
    private fun parseExpectedClosing(kind: SyntaxKind, openPos: Int): Boolean {
        if (token == kind) {
            if (openTokenStack.isNotEmpty()) openTokenStack.removeAt(openTokenStack.lastIndex)
            nextToken(); return true
        }
        val openToken = when (kind) {
            SyntaxKind.CloseBrace -> "{"
            SyntaxKind.CloseBracket -> "["
            else -> "("
        }
        val closeToken = tokenToString(kind)
        if (openTokenStack.isNotEmpty()) openTokenStack.removeAt(openTokenStack.lastIndex)
        reportErrorWithRelatedInfo(
            "'$closeToken' expected.", 1005,
            "The parser expected to find a '$closeToken' to match the '$openToken' token here.",
            1007, openPos
        )
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
        // ASI: implicit at }, EOF, or after line break. In a few well-defined cases
        // the current token unambiguously cannot continue a statement — TypeScript
        // emits TS1005 "';' expected." at that position. Restricted to `:` plus
        // numeric/bigint literals (e.g. `2n2` scans as `2n` `2`) to avoid noisy
        // regressions in broader error-recovery paths. Covers patterns like
        // `this.foo: any;` (mis-typed class-field declaration inside a body).
        if ((token == SyntaxKind.Colon || token == SyntaxKind.NumericLiteral ||
                token == SyntaxKind.BigIntLiteral) && !scanner.hasPrecedingLineBreak()) {
            val len = if (token == SyntaxKind.Colon) 1
                else scanner.getTokenText().length.coerceAtLeast(1)
            reportError("';' expected.", code = 1005,
                overrideStart = scanner.getTokenPos(), overrideLength = len)
        }
    }

    /**
     * Parses an optional import attribute clause: `assert { ... }` or `with { ... }`.
     * Returns the raw source text of the clause (including the keyword and braces),
     * or null if not present. Used to preserve assertions in esnext output.
     */
    /** Set by [parseImportAttributes] to the source position of the `assert`/`with` keyword
     *  of the most recently parsed clause, or -1 when none. Read at the call site immediately
     *  after the call to capture it onto the declaration node. */
    private var lastImportAttributesPos: Int = -1

    private fun parseImportAttributes(): String? {
        lastImportAttributesPos = -1
        // `assert` is not a keyword — check as identifier value
        val isAssert = token == SyntaxKind.Identifier && scanner.getTokenValue() == "assert"
        val isWith = token == SyntaxKind.WithKeyword
        if (!isAssert && !isWith) return null
        val startPos = scanner.getTokenPos()
        nextToken() // consume 'assert' or 'with'
        if (token != SyntaxKind.OpenBrace) return null
        // Skip balanced braces { ... }
        var depth = 0
        while (token != SyntaxKind.EndOfFile) {
            when (token) {
                SyntaxKind.OpenBrace -> { depth++; nextToken() }
                SyntaxKind.CloseBrace -> {
                    depth--
                    nextToken()
                    if (depth == 0) {
                        lastImportAttributesPos = startPos
                        return source.substring(startPos, scanner.getTokenPos()).trimEnd()
                    }
                }
                else -> nextToken()
            }
        }
        return null
    }

    private fun canParseSemicolon(): Boolean =
        token == SyntaxKind.Semicolon ||
                token == SyntaxKind.CloseBrace ||
                token == SyntaxKind.EndOfFile ||
                scanner.hasPrecedingLineBreak()

    /** Reports error at the end of the PREVIOUS token (e.g., at `)` after parsing parameter list). */
    private fun reportErrorAtPrevTokenEnd(message: String, code: Int = 1005) {
        val start = (scanner.getPrevTokenEnd() - 1).coerceAtLeast(0)
        val (line, character) = getLineAndCharacterOfPosition(start)
        diagnostics.add(
            Diagnostic(
                message = message,
                category = DiagnosticCategory.Error,
                code = code,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = 1,
            )
        )
    }

    /**
     * 16.0: Detect `/// <reference path="self.ts" />` self-reference and emit TS1006.
     * Also detects malformed `<reference ... />` directives and emits TS1084.
     * Scans the file's leading triple-slash directives without using the parser/scanner.
     */
    private fun checkTripleSlashSelfReference() {
        val ownBaseName = fileName.substringAfterLast('/')
        val refRegex = Regex("""///\s*<reference\s+path\s*=\s*["']([^"']+)["']""")
        val validRefRegex = Regex("""^\s*///\s*<reference(?:\s+[A-Za-z-]+\s*=\s*(?:"[^"]*"|'[^']*'))+\s*/>\s*$""")
        val referenceStartRegex = Regex("""^\s*///\s*<reference\b""")
        var pos = 0
        for (line in source.lineSequence()) {
            val trimmed = line.trimStart()
            if (!trimmed.startsWith("///")) {
                if (trimmed.isNotEmpty()) break // first non-triple-slash, non-blank line stops scan
                pos += line.length + 1
                continue
            }
            // TS1084: Malformed <reference ...> directive
            if (referenceStartRegex.containsMatchIn(line) && !validRefRegex.matches(line)) {
                val leadingWs = line.length - trimmed.length
                val absStart = pos + leadingWs
                val absLen = trimmed.trimEnd().length
                val (line0, char0) = getLineAndCharacterOfPosition(absStart)
                diagnostics.add(
                    Diagnostic(
                        message = "Invalid 'reference' directive syntax.",
                        category = DiagnosticCategory.Error,
                        code = 1084,
                        fileName = fileName,
                        line = line0,
                        character = char0,
                        start = absStart,
                        length = absLen,
                    )
                )
            }
            val match = refRegex.find(trimmed)
            if (match != null) {
                val refPath = match.groupValues[1]
                // Only flag exact-name self-reference. Strip leading `./` for the check.
                // Paths with `..` need full resolution, handled elsewhere.
                val normalizedRef = if (refPath.startsWith("./")) refPath.substring(2) else refPath
                if (normalizedRef == ownBaseName && !refPath.contains("..")) {
                    // Position the error at the path string (excluding the surrounding quotes)
                    val pathStartInLine = line.indexOf(refPath)
                    if (pathStartInLine >= 0) {
                        val absStart = pos + pathStartInLine
                        val absLen = refPath.length
                        val (line0, char0) = getLineAndCharacterOfPosition(absStart)
                        diagnostics.add(
                            Diagnostic(
                                message = "A file cannot have a reference to itself.",
                                category = DiagnosticCategory.Error,
                                code = 1006,
                                fileName = fileName,
                                line = line0,
                                character = char0,
                                start = absStart,
                                length = absLen,
                            )
                        )
                    }
                }
            }
            pos += line.length + 1
        }
    }

    private fun reportError(message: String, code: Int = 1005, overrideLength: Int? = null, overrideStart: Int? = null) {
        val start = overrideStart ?: scanner.getTokenPos()
        // TypeScript suppresses duplicate errors at the same source position
        // (mirrors the `start !== lastError.start` check in TypeScript's parseErrorAtPosition)
        val lastDiag = diagnostics.lastOrNull()
        if (lastDiag != null && lastDiag.start == start) return
        val length = overrideLength ?: (scanner.getPos() - start).coerceAtLeast(0)
        val (line, character) = getLineAndCharacterOfPosition(start)
        diagnostics.add(
            Diagnostic(
                message = message,
                category = DiagnosticCategory.Error,
                code = code,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = length,
            )
        )
    }

    private fun reportErrorWithRelatedInfo(
        message: String, code: Int,
        relatedMessage: String, relatedCode: Int, relatedPos: Int,
    ) {
        val start = scanner.getTokenPos()
        val length = (scanner.getPos() - start).coerceAtLeast(0)
        val (line, character) = getLineAndCharacterOfPosition(start)
        val (relLine, relChar) = getLineAndCharacterOfPosition(relatedPos)
        diagnostics.add(
            Diagnostic(
                message = message,
                category = DiagnosticCategory.Error,
                code = code,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = length,
                relatedInformation = listOf(
                    Diagnostic(
                        message = relatedMessage,
                        category = DiagnosticCategory.Error,
                        code = relatedCode,
                        fileName = fileName,
                        line = relLine,
                        character = relChar,
                        start = relatedPos,
                        length = 1,
                    )
                ),
            )
        )
    }

    /**
     * Compute 1-based line and character for a position in the source.
     */
    private fun getLineAndCharacterOfPosition(position: Int): Pair<Int, Int> {
        var low = 0
        var high = lineStarts.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lineStarts[mid] <= position) low = mid + 1
            else high = mid - 1
        }
        val lineIndex = low - 1 // 0-based line index
        return (lineIndex + 1) to (position - lineStarts[lineIndex] + 1)
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

    private fun parseStatements(topLevel: Boolean = false): List<Statement> {
        val stmts = mutableListOf<Statement>()
        while (token != SyntaxKind.EndOfFile) {
            if (token == SyntaxKind.CloseBrace) {
                if (topLevel) {
                    // Stray '}' at file top level — emit TS1128 and skip
                    reportError("Declaration or statement expected.", code = 1128)
                    nextToken()
                    continue
                } else {
                    break // Normal block termination
                }
            }
            // Error recovery: when inside a class body and we encounter `static` followed
            // by an identifier, terminate the block — the enclosing class body parser will
            // parse it as a class member (matches TypeScript's error recovery behavior).
            if (classBodyDepth > 0 && token == SyntaxKind.StaticKeyword &&
                lookAhead { nextToken(); isIdentifier() }
            ) {
                reportError("Declaration or statement expected.", code = 1128)
                break
            }
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
        VarKeyword -> parseVariableStatement()
        // `let` is a declaration keyword only when followed by an identifier, `[`, or `{`.
        // `let = 30` uses `let` as an identifier — fall through to expression parsing.
        LetKeyword -> if (lookAhead { nextToken(); token == OpenBracket || token == OpenBrace || isIdentifier() }) {
            parseVariableStatement()
        } else {
            parseExpressionStatement()
        }
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
        ImportKeyword -> {
            // import( = dynamic import call; import. = import.meta — parse as expression
            val nextIsParen = scanner.lookAhead { scanner.scan(); scanner.getToken() == SyntaxKind.OpenParen }
            val nextIsDot = scanner.lookAhead { scanner.scan(); scanner.getToken() == SyntaxKind.Dot }
            // B22.1: when the token after `import` is clearly not a valid import-clause start
            // (e.g. `import 10;` — numeric/bigint/regex literal), emit TS1128 at the `import`
            // keyword and skip only the keyword so the remainder parses as a regular statement.
            // Valid import starts: StringLiteral (side-effect), Identifier (default name),
            // OpenBrace (named), Asterisk (namespace), TypeKeyword (type-only).
            val nextIsInvalid = scanner.lookAhead {
                scanner.scan()
                when (scanner.getToken()) {
                    SyntaxKind.NumericLiteral, SyntaxKind.BigIntLiteral,
                    SyntaxKind.RegularExpressionLiteral -> true
                    else -> false
                }
            }
            if (nextIsParen || nextIsDot) parseExpressionStatement()
            else if (nextIsInvalid) {
                reportError("Declaration or statement expected.", code = 1128, overrideLength = 6)
                nextToken()
                null
            }
            else parseImportDeclaration()
        }
        ExportKeyword -> parseExportDeclaration()
        InterfaceKeyword -> if (lookAhead { nextToken(); isIdentifier() || isKeyword() }) {
            parseInterfaceDeclaration()
        } else {
            parseExpressionStatement()
        }
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
                    token != SyntaxKind.EndOfFile && token != SyntaxKind.OpenBrace
            }
            // B66.2: `module { ... }` (legacy anonymous-module syntax) — emit
            // TS1437 "Namespace must be given a name." at the `{` position.
            // The body is still parsed as expression statement + block; TS2591
            // fires on the `module` identifier via checkIdentifierResolved.
            // Emit inside lookAhead — scanner state restores but the diagnostic
            // persists.
            lookAhead {
                nextToken()
                if (token == SyntaxKind.OpenBrace && !scanner.hasPrecedingLineBreak()) {
                    reportError("Namespace must be given a name.", code = 1437,
                        overrideStart = scanner.getTokenPos(), overrideLength = 1)
                }
                true
            }
            if (isDecl) parseModuleDeclaration() else parseExpressionStatement()
        }
        AbstractKeyword -> parseAbstractOrDeclaration()
        AsyncKeyword -> parseAsyncOrExpression()
        DeclareKeyword -> {
            // Check if next token could start a declaration. If not, treat 'declare' as an identifier.
            // Also: if there's a line break between 'declare' and the keyword, ASI applies.
            val isDeclare = lookAhead {
                nextToken()
                if (scanner.hasPrecedingLineBreak()) false
                else when (token) {
                    VarKeyword, LetKeyword, ConstKeyword, FunctionKeyword, ClassKeyword,
                    InterfaceKeyword, TypeKeyword, EnumKeyword, NamespaceKeyword,
                    AbstractKeyword, GlobalKeyword, ImportKeyword, ExportKeyword,
                    DeclareKeyword -> true
                    // `declare module { ... }` (no name) is NOT a valid module declaration;
                    // fall back to treating `declare` as an identifier so the parser
                    // recovers it as three separate statements: `declare;`, `module;`, `{ ... }`.
                    ModuleKeyword -> {
                        nextToken()
                        token != OpenBrace
                    }
                    else -> false
                }
            }
            if (isDeclare) parseDeclareDeclaration() else parseExpressionStatement()
        }
        CaseKeyword -> {
            // 17.205: TS1128 — `case` outside a switch is a stray keyword.
            // Report at the keyword position with length 4 then skip it for
            // error recovery so subsequent statements parse.
            reportError("Declaration or statement expected.", code = 1128, overrideLength = 4)
            nextToken()
            null
        }
        CloseParen, CloseBracket -> {
            // Stray `)` / `]` at statement-start position is leftover after a
            // broken paren / bracket expression (e.g. `(y = 1; 2)` parses `(y=1`
            // with TS1005 `)' expected, then `2` as ExpressionStatement, and is
            // left at `)`). TypeScript emits TS1005 ";' expected." here rather
            // than TS1109 "Expression expected." — the closing delimiter signals
            // a missing statement terminator, not a missing expression.
            reportError("';' expected.", code = 1005, overrideLength = 1)
            nextToken()
            null
        }
        DefaultKeyword -> parseDefaultStartedStatement(fromDecorated = false)
        At -> {
            // Capture leading comments (e.g. JSDoc) that appear before the first decorator.
            // They would otherwise be lost since parseDecorators() doesn't call leadingComments().
            val outerComments = leadingComments()
            val decs = parseDecorators()
            val stmt = parseDecoratedStatement(decs)
            // Attach captured comments to the resulting statement
            if (outerComments != null && stmt is ClassDeclaration) {
                val merged = (outerComments + (stmt.leadingComments ?: emptyList())).ifEmpty { null }
                stmt.copy(leadingComments = merged)
            } else stmt
        }
        SyntaxKind.LabeledStatement -> null // won't appear as token
        PrivateKeyword, PublicKeyword, ProtectedKeyword -> {
            // Access modifier keywords in statement context (e.g. `private y = x;` in constructor).
            // TypeScript treats these as property declarations; skip the modifier and parse the rest.
            // Also handle `public this.p1 = 0;` pattern (modifier before `this`).
            // When NOT in a class body (e.g., in a namespace), report TS1128 and parse the rest.
            val hasModifier = lookAhead { nextToken(); isIdentifier() || token == SyntaxKind.ThisKeyword }
            if (hasModifier && classBodyDepth == 0) {
                // Outside a class, `private`/`public`/`protected` starts are invalid declarations
                reportError("Declaration or statement expected.", code = 1128)
            }
            if (hasModifier) nextToken()
            val stmt = parseExpressionStatement()
            // When inside a class body and the modifier was followed by a bare identifier assignment
            // (e.g. `public p1 = 0;`), add `this.` prefix to match TypeScript's output.
            if (hasModifier && classBodyDepth > 0 && stmt != null) {
                val expr = stmt.expression
                if (expr is BinaryExpression && expr.operator == SyntaxKind.Equals &&
                    expr.left is Identifier
                ) {
                    val propAccess = PropertyAccessExpression(
                        expression = Identifier(text = "this", pos = -1, end = -1),
                        name = expr.left,
                        pos = -1, end = -1,
                    )
                    stmt.copy(expression = expr.copy(left = propAccess))
                } else stmt
            } else stmt
        }
        else -> {
            if (isIdentifier() && lookAhead { nextToken(); token == Colon }) {
                parseLabeledStatement()
            } else {
                parseExpressionStatement()
            }
        }
    }

    /**
     * After parsing decorators (@dec), parse the declaration they attach to.
     * For class declarations, attach the decorators to the ClassDeclaration node.
     * For export class, the result is a ClassDeclaration with Export modifier —
     * decorators are attached after parsing.
     */
    private fun parseDecoratedStatement(decorators: List<Decorator>?): Statement? {
        // `@decorator default class {}` — in decorator context, treat `default` as a
        // present-but-misordered modifier (TS1029). Without decorators, bare `default`
        // at statement start is a missing-export syntax error — handled in parseStatement.
        val stmt = if (token == SyntaxKind.DefaultKeyword) {
            parseDefaultStartedStatement(fromDecorated = true)
        } else parseStatement()
        if (stmt is ClassDeclaration && decorators != null) {
            return stmt.copy(decorators = decorators)
        }
        return stmt
    }

    /**
     * Parses a statement that begins with `default` (where `export` is missing).
     * - [fromDecorated] = true: decorators precede (e.g. `@decorator default class {}`) —
     *   emit TS1029 "'export' modifier must precede 'default' modifier." and parse as
     *   an anonymous default-exported declaration.
     * - [fromDecorated] = false: bare `default` at statement start — emit TS1005
     *   "'export' expected." at the `default` keyword and parse the trailing declaration
     *   WITHOUT the Default modifier, so a missing function/class name naturally fires
     *   TS1003 "Identifier expected." (mirroring TypeScript's behavior).
     */
    private fun parseDefaultStartedStatement(fromDecorated: Boolean): Statement? {
        val defaultStart = getPos()
        if (fromDecorated) {
            reportError("'export' modifier must precede 'default' modifier.", code = 1029,
                overrideStart = defaultStart, overrideLength = "default".length)
        } else {
            reportError("'export' expected.", code = 1005,
                overrideStart = defaultStart, overrideLength = "default".length)
        }
        nextToken()
        val mods = if (fromDecorated) setOf(ModifierFlag.Default) else emptySet()
        return when (token) {
            FunctionKeyword -> parseFunctionDeclarationOrExpression(mods)
            ClassKeyword -> parseClassDeclaration(mods)
            AsyncKeyword -> {
                nextToken()
                parseFunctionDeclarationOrExpression(
                    if (fromDecorated) mods + ModifierFlag.Async else setOf(ModifierFlag.Async)
                )
            }
            else -> parseExpressionStatement()
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
        return Block(statements = allStmts, multiLine = multiLine, openBraceTrailingComments = openBraceTrailingComments, pos = pos, end = getEnd(), leadingComments = comments, closeBracePos = closeBracePos)
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
        var declList = parseVariableDeclarationList()
        // 17.62 / 17.65: in JS-like files, a single-declarator VariableStatement
        // with no explicit type and a leading JSDoc `@type {T}` comment supplies
        // T as the declaration's type. 17.65 widens beyond 17.62's primitive-only
        // allowlist by routing through the full sub-Parser (`parsePropertyTypeFromJSDoc`)
        // — name-resolution diagnostics on the resulting sub-Parser-derived
        // TypeNode are suppressed via the `decl.typeFromJSDoc` gate in the
        // checker's `checkUnresolvedInStatement` VariableStatement branch (avoiding
        // 17.61's spurious-TS2503-with-garbled-squiggle regression).
        if (isJsLikeFile && declList.declarations.size == 1) {
            val decl = declList.declarations[0]
            if (decl.type == null && decl.name is Identifier) {
                val jsdocType = parsePropertyTypeFromJSDoc(comments)
                // B286: bare `Array`/`Promise` and closure-style `Object<K,V>` JSDoc
                // types mean any[]/Promise<any>/string-indexed-any in JS — bridging
                // them as annotations produces unresolved-T TS2322 / TS2315 FPs (the
                // sub-parser positions are also substring-relative). The noImplicitAny
                // TS2314 for the bare forms is raw-scanned by checkJsDocBareGenericTags.
                val skipBridge = jsdocType is TypeReference &&
                    (jsdocType.typeName as? Identifier)?.text?.let { n ->
                        ((n == "Array" || n == "Promise") && jsdocType.typeArguments.isNullOrEmpty()) ||
                            (n == "Object" && !jsdocType.typeArguments.isNullOrEmpty())
                    } == true
                if (jsdocType != null && !skipBridge) {
                    val newDecl = decl.copy(type = jsdocType, typeFromJSDoc = true)
                    declList = declList.copy(declarations = listOf(newDecl))
                }
            }
        }
        // Capture same-line trailing comments between last declaration and `;`
        // (e.g. `/*number*/` in `var z = x.then() /*number*/; // comment`)
        val semiInline = if (!scanner.hasPrecedingLineBreak()) scanner.consumeTrailingComments() else null
        parseSemicolon()
        val trailing = trailingComments()
        return VariableStatement(
            declarationList = declList,
            modifiers = modifiers,
            pos = pos,
            end = getEnd(),
            leadingComments = comments,
            trailingComments = trailing,
            preSemicolonComments = semiInline
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
            while (true) {
                if (parseOptional(SyntaxKind.Comma)) {
                    decls.add(parseVariableDeclaration())
                } else if ((isIdentifier() || token == SyntaxKind.OpenBrace || token == SyntaxKind.OpenBracket)
                    && decls.last().initializer != null
                    && !scanner.hasPrecedingLineBreak()) {
                    // Error recovery: missing comma before next declarator when on same line with
                    // an initializer (e.g. `var x = /re/ i` — regex literal followed by identifier).
                    // Emit TS1005 ',' expected. Don't recover across line breaks or without initializer,
                    // to avoid misinterpreting `for (let x of ...)` or multi-line code.
                    val lastInit = decls.last().initializer
                    val isNumericLitWithTrailingDot = lastInit is NumericLiteralNode && lastInit.text.endsWith(".")
                    if (isNumericLitWithTrailingDot) {
                        // B61.3: After TS1351 (numeric-literal-followed-by-identifier),
                        // defer TS1005 to land on the next token after the identifier
                        // (typically `(` in `var x = 2.toString();`).
                        decls.add(parseVariableDeclaration())
                        reportError("',' expected.", code = 1005)
                    } else {
                        parseExpected(SyntaxKind.Comma)
                        decls.add(parseVariableDeclaration())
                    }
                } else if (token == SyntaxKind.Unknown && !scanner.hasPrecedingLineBreak() &&
                    lookAhead { nextToken(); isIdentifier() || token == SyntaxKind.OpenBrace || token == SyntaxKind.OpenBracket }) {
                    // Error recovery: invalid character (like `\` from an incomplete unicode escape)
                    // appears in place of a comma in a var declaration list.
                    // E.g., `var arg\uxxxx` → treat as `var arg, uxxxx` (matches TypeScript behavior).
                    reportError("Invalid character.", code = 1127, overrideLength = 0)
                    nextToken() // consume the Unknown token (the invalid character)
                    decls.add(parseVariableDeclaration())
                } else {
                    break
                }
            }
        } else {
            // Report error but produce empty declarations list (e.g. bare `let;`)
            reportError("Variable declaration list cannot be empty.", code = 1123)
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
        val initLeadingTrailing: List<Comment>?
        val init: Expression?
        if (parseOptional(SyntaxKind.Equals)) {
            // Capture inline comments between `=` and the initializer value
            // (e.g. `= // should error\n   [1,2,3]`). These are trailing-of-`=`
            // line comments that terminate the line before the initializer.
            // Only meaningful when the initializer is on a new line — otherwise
            // existing parsePrimaryExpression inlineCmts path handles them.
            initLeadingTrailing = scanner.getTrailingComments()
                ?.filter { !it.hasPrecedingNewLine && it.text.startsWith("//") && it.hasTrailingNewLine }
                ?.takeIf { it.isNotEmpty() && scanner.hasPrecedingLineBreak() }
            init = parseAssignmentExpression()
        } else {
            initLeadingTrailing = null
            init = null
        }
        return VariableDeclaration(
            name = name,
            type = type,
            initializer = init,
            exclamationToken = excl,
            pos = pos,
            end = getEnd(),
            nameTrailingComments = nameTrailing,
            initializerLeadingTrailingComments = initLeadingTrailing,
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
            var element = parseBindingElement(inObjectPattern = true)
            if (parseOptional(SyntaxKind.Comma)) {
                // After consuming the comma, the scanner has advanced to the next token.
                // Trailing comments on the same line as the comma belong to the preceding element.
                val afterCommaTrailing = scanner.getTrailingComments()
                if (afterCommaTrailing != null && element.trailingComments == null) {
                    element = element.copy(trailingComments = afterCommaTrailing)
                }
                hasTrailingComma = (token == SyntaxKind.CloseBrace)
            } else {
                hasTrailingComma = false
                elements.add(element)
                // tsc parseDelimitedList recovery: when the element ended with a MISSING
                // binding name (property-name + missing ':' recovery shape), keep scanning —
                // a token that can start another element re-enters the list (the ',' expected
                // error is same-start-suppressed when it collides with the ':' expected one);
                // anything else is skipped one token at a time (TS1180, also usually deduped).
                // pos == end distinguishes the property-name recovery's synthesized
                // zero-width missing name from parseIdentifier's TS1003 empty-name
                // fallback (width >= 1) — the latter must NOT trigger list recovery
                // (it changes how far speculative lookAhead parses advance, e.g.
                // `type A = ({x:(...) => T}["x"])`).
                val nameMissing = element.propertyName != null &&
                    (element.name as? Identifier)?.let { it.text.isEmpty() && it.pos == it.end } == true
                if (nameMissing && token != SyntaxKind.CloseBrace && token != SyntaxKind.EndOfFile) {
                    val canStartElement = token == SyntaxKind.DotDotDot ||
                        token == SyntaxKind.OpenBracket ||
                        token == SyntaxKind.StringLiteral ||
                        token == SyntaxKind.NumericLiteral ||
                        token == SyntaxKind.BigIntLiteral ||
                        isIdentifier() || isKeyword()
                    if (canStartElement) {
                        reportError("',' expected.", code = 1005)
                        continue
                    }
                    // A token an OUTER parsing context owns aborts the list (tsc
                    // abortParsingListOrMoveToNextToken) instead of being consumed.
                    if (token == SyntaxKind.CloseParen || token == SyntaxKind.CloseBracket) break
                    reportError("Property destructuring pattern expected.", code = 1180)
                    nextToken()
                    continue
                }
                break
            }
            elements.add(element)
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

    private fun parseBindingElement(inObjectPattern: Boolean = false): BindingElement {
        val pos = getPos()
        // Capture leading comments before `...` or the element name (e.g. `// Omit` before `foo`)
        val elemComments = if (!dotDotDotToken()) leadingComments() else null
        val dotDotDot = parseOptional(SyntaxKind.DotDotDot)
        val postDotComments = if (dotDotDot) leadingComments() ?: scanner.getTrailingComments() else null
        // Detect computed property name [expr]: x or string/numeric property name "foo": x / 0: x
        val nameOrPropIsPropertyKey = when (token) {
            SyntaxKind.OpenBracket -> {
                // Save diagnostics count — parseComputedPropertyName() inside lookAhead may
                // report false TS1005 when [a = v, b = v] is a binding pattern (not computed property),
                // because parseAssignmentExpression stops at comma, leaving ']' unfound.
                val savedDiagCount = diagnostics.size
                val result = lookAhead { parseComputedPropertyName(); token == SyntaxKind.Colon }
                // Discard any diagnostics reported speculatively during the lookAhead
                while (diagnostics.size > savedDiagCount) diagnostics.removeAt(diagnostics.lastIndex)
                result
            }
            SyntaxKind.StringLiteral -> lookAhead { nextToken(); token == SyntaxKind.Colon }
            SyntaxKind.NumericLiteral, SyntaxKind.BigIntLiteral -> lookAhead { nextToken(); token == SyntaxKind.Colon }
            else -> false
        }
        // A strict reserved word (e.g. `return`) cannot be a binding NAME — in tsc it is
        // always a property name awaiting `: bindingName` (parseObjectBindingElement's
        // tokenIsIdentifier=false path). Track it for the no-colon recovery below.
        val reservedWordStart = inObjectPattern && isKeyword() && !isIdentifier()
        var nameOrProp: Expression = when {
            nameOrPropIsPropertyKey && token == SyntaxKind.OpenBracket -> parseComputedPropertyName()
            (nameOrPropIsPropertyKey || inObjectPattern) && token == SyntaxKind.StringLiteral -> parseStringLiteral()
            (nameOrPropIsPropertyKey || inObjectPattern) && token == SyntaxKind.NumericLiteral -> parseNumericLiteral()
            (nameOrPropIsPropertyKey || inObjectPattern) && token == SyntaxKind.BigIntLiteral -> {
                val bPos = getPos(); val text = scanner.getTokenValue(); nextToken()
                BigIntLiteralNode(text = text, pos = bPos, end = getEnd())
            }
            else -> parseBindingNameOrPattern()
        }
        if (postDotComments != null) nameOrProp = nameOrProp.withLeadingComments(postDotComments)
        // Recovery (tsc parseObjectBindingElement): the element started with a token that can
        // only be a property NAME (reserved word / string / numeric literal) but no ':' follows.
        // Emit TS1005 ':' expected at the current token and synthesize a MISSING binding name
        // (zero-width Identifier at the previous token's end) — the checker renders it '(Missing)'.
        if (token != SyntaxKind.Colon &&
            (reservedWordStart || nameOrProp is StringLiteralNode || nameOrProp is NumericLiteralNode
                || nameOrProp is BigIntLiteralNode)) {
            parseExpected(SyntaxKind.Colon)
            val missingPos = scanner.getPrevTokenEnd()
            val name: Expression = if (isIdentifier() || token == SyntaxKind.OpenBrace || token == SyntaxKind.OpenBracket)
                parseBindingNameOrPattern()
            else Identifier(text = "", pos = missingPos, end = missingPos)
            val init = if (parseOptional(SyntaxKind.Equals)) parseAssignmentExpression() else null
            return BindingElement(
                propertyName = nameOrProp,
                name = name,
                initializer = init,
                dotDotDotToken = dotDotDot,
                pos = pos,
                end = getEnd(),
                leadingComments = elemComments,
            )
        }
        return if (token == SyntaxKind.Colon &&
            (nameOrProp is Identifier || nameOrProp is ComputedPropertyName
                || nameOrProp is StringLiteralNode || nameOrProp is NumericLiteralNode
                || nameOrProp is BigIntLiteralNode)) {
            nextToken()
            val name = parseBindingNameOrPattern()
            val init = if (parseOptional(SyntaxKind.Equals)) parseAssignmentExpression() else null
            val elemTrailing = if (init != null) scanner.getTrailingComments() else null
            BindingElement(
                propertyName = nameOrProp,
                name = name,
                initializer = init,
                dotDotDotToken = dotDotDot,
                pos = pos,
                end = getEnd(),
                leadingComments = elemComments,
                trailingComments = elemTrailing,
            )
        } else {
            val init = if (parseOptional(SyntaxKind.Equals)) parseAssignmentExpression() else null
            val elemTrailing = if (init != null) scanner.getTrailingComments() else null
            BindingElement(
                name = nameOrProp,
                initializer = init,
                dotDotDotToken = dotDotDot,
                pos = pos,
                end = getEnd(),
                leadingComments = elemComments,
                trailingComments = elemTrailing,
            )
        }
    }

    /** True if the current token is `...` (DotDotDot) — used to decide whether to capture leading comments first. */
    private fun dotDotDotToken(): Boolean = token == SyntaxKind.DotDotDot

    /**
     * B66.3: True if [expr] is a PropertyAccessExpression rooted at an Identifier
     * named `module` (or other node-builtin identifier). Used to gate TS1005
     * emission for `module.X { ... }` patterns without regressing parser
     * error-recovery paths.
     */
    private fun isModuleRootedPropertyAccess(expr: Expression): Boolean {
        var cur: Expression = expr
        while (cur is PropertyAccessExpression) {
            cur = cur.expression
        }
        return cur is Identifier && cur.text == "module"
    }

    private fun parseExpressionStatement(): ExpressionStatement {
        val pos = getPos()
        val comments = leadingComments()
        val expr = parseExpression()
        // Capture same-line trailing comments between expression and `;`
        // (e.g. the `/*3*/` in `new Array /*3*/;`) before parseSemicolon advances past them.
        // Only when no preceding line break — comments on a new line belong to the next statement.
        val semiInline = if (!scanner.hasPrecedingLineBreak()) scanner.consumeTrailingComments()?.ifEmpty { null } else null
        // B66.3: `module.X { ... }` on the same line (e.g. `module.module { }`) —
        // the next `{` starts a block statement, not a continuation. Emit TS1005
        // "';' expected." at the `{` token. Narrow gate: expression is a
        // PropertyAccessExpression rooted at `module` (a NODE_BUILTIN global) —
        // avoids regressing parser error-recovery paths in class/interface bodies.
        if (token == SyntaxKind.OpenBrace && !scanner.hasPrecedingLineBreak() &&
            isModuleRootedPropertyAccess(expr)) {
            reportError("';' expected.", code = 1005,
                overrideStart = scanner.getTokenPos(), overrideLength = 1)
        }
        // B288: a NUMERIC-literal expression statement followed by an identifier on the
        // same line (`00e5;` scans as `00` `e5` — legacy octal / leading-zero literals
        // don't absorb the identifier) is TS1005 "';' expected." at the identifier.
        // Same-start dedup mirrors tsc parseErrorAtPosition (a TS1351/TS1124 already
        // at the identifier's position suppresses it).
        if (expr is NumericLiteralNode && isIdentifier() && !scanner.hasPrecedingLineBreak() &&
            diagnostics.lastOrNull()?.start != scanner.getTokenPos()) {
            reportError("';' expected.", code = 1005,
                overrideStart = scanner.getTokenPos(),
                overrideLength = scanner.getTokenText().length.coerceAtLeast(1))
        }
        // B291: tsc parseErrorForMissingSemicolonAfter — a bare-Identifier expression
        // statement followed on the same line by a DOT-LEADING numeric/bigint literal
        // (`g.2n` scans as `g` + `.2n`) reports TS1434 "Unexpected keyword or
        // identifier." at the IDENTIFIER, replacing the TS1005 — which also stops the
        // same-start dedup from swallowing the literal's own scanner diagnostic
        // (TS1353 at the literal). Narrow dot-gate: other `ident <numeric>` shapes
        // keep the TS1005 path.
        val missingSemiAsUnexpectedIdent = expr is Identifier && !scanner.hasPrecedingLineBreak() &&
            (token == SyntaxKind.NumericLiteral || token == SyntaxKind.BigIntLiteral) &&
            scanner.getTokenText().startsWith(".")
        if (missingSemiAsUnexpectedIdent) {
            reportError("Unexpected keyword or identifier.", code = 1434,
                overrideStart = (expr as Identifier).pos, overrideLength = expr.text.length.coerceAtLeast(1))
        } else {
            parseSemicolon()
        }
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
        val afterKeyword = trailingComments()
        val openParenPos = scanner.getTokenPos()
        parseExpected(SyntaxKind.OpenParen)
        val afterOpenParen = trailingComments()
        val expr = parseExpression()
        val beforeCloseParen = trailingComments()
        parseExpectedClosing(SyntaxKind.CloseParen, openParenPos)
        val afterCloseParen = trailingComments()
        val thenStmt = parseStatement() ?: EmptyStatement()
        // 17.176: TS1313 — `if (cond);` (then-body is `;`). Distinguish a real
        // EmptyStatement (parseEmptyStatement set pos to the `;` position) from
        // the `?: EmptyStatement()` fallback above (synthetic, pos=0).
        if (thenStmt is EmptyStatement && thenStmt.pos > 0) {
            reportError(
                "The body of an 'if' statement cannot be the empty statement.",
                code = 1313, overrideStart = thenStmt.pos, overrideLength = 1,
            )
        }
        // Capture trailing comments from the then-block's closing brace BEFORE checking for else.
        // This way `if (p) { } // err` captures the comment even when `else` follows on the next line.
        // We must read now because nextToken() in parseOptional/ElseKeyword will reset trailingComments.
        // Only capture if thenStmt didn't already capture them (e.g., block statements don't capture trailing).
        val beforeElse = if (token == SyntaxKind.ElseKeyword) {
            // Capture both trailing comments (inline, no newline) and leading comments
            // (on their own line) before the `else` keyword.
            val trailing = trailingComments()
            val leading = leadingComments()
            when {
                trailing != null && leading != null -> trailing + leading
                trailing != null -> trailing
                else -> leading
            }
        } else null
        val thenTrailing = if (token != SyntaxKind.ElseKeyword && thenStmt.trailingComments == null) {
            trailingComments()
        } else null
        val hasElse = parseOptional(SyntaxKind.ElseKeyword)
        val afterElse = if (hasElse) trailingComments() else null
        val elseStmt = if (hasElse) parseStatement() else null
        val trailing = if (elseStmt != null) trailingComments() else thenTrailing
        return IfStatement(
            expression = expr,
            thenStatement = thenStmt,
            elseStatement = elseStmt,
            afterKeywordComments = afterKeyword,
            afterOpenParenComments = afterOpenParen,
            beforeCloseParenComments = beforeCloseParen,
            afterCloseParenComments = afterCloseParen,
            beforeElseComments = beforeElse,
            afterElseComments = afterElse,
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
        val afterDo = trailingComments()
        val stmt = parseStatement() ?: EmptyStatement()
        val beforeWhile = trailingComments()
        parseExpected(SyntaxKind.WhileKeyword)
        val afterWhile = trailingComments()
        val doOpenParenPos = scanner.getTokenPos()
        parseExpected(SyntaxKind.OpenParen)
        val afterOpenParen = trailingComments()
        val expr = parseExpression()
        val beforeCloseParen = trailingComments()
        parseExpectedClosing(SyntaxKind.CloseParen, doOpenParenPos)
        val afterCloseParen = trailingComments()
        parseSemicolon()
        val trailing = trailingComments()
        return DoStatement(
            statement = stmt, expression = expr,
            afterDoComments = afterDo, beforeWhileComments = beforeWhile, afterWhileComments = afterWhile,
            afterOpenParenComments = afterOpenParen,
            beforeCloseParenComments = beforeCloseParen,
            afterCloseParenComments = afterCloseParen,
            pos = pos, end = getEnd(), leadingComments = comments, trailingComments = trailing,
        )
    }

    private fun parseWhileStatement(): WhileStatement {
        val pos = getPos()
        val comments = leadingComments()
        parseExpected(SyntaxKind.WhileKeyword)
        val afterKeyword = trailingComments()
        val openParenPos = scanner.getTokenPos()
        parseExpected(SyntaxKind.OpenParen)
        val afterOpenParen = trailingComments()
        val expr = parseExpression()
        val beforeCloseParen = trailingComments()
        parseExpectedClosing(SyntaxKind.CloseParen, openParenPos)
        val afterCloseParen = trailingComments()
        val stmt = parseStatement() ?: EmptyStatement()
        return WhileStatement(
            expression = expr,
            statement = stmt,
            afterKeywordComments = afterKeyword,
            afterOpenParenComments = afterOpenParen,
            beforeCloseParenComments = beforeCloseParen,
            afterCloseParenComments = afterCloseParen,
            pos = pos,
            end = getEnd(),
            leadingComments = comments
        )
    }

    private fun parseForStatement(): Statement {
        val pos = getPos()
        val comments = leadingComments()
        parseExpected(SyntaxKind.ForKeyword)
        val afterKeyword = trailingComments()
        val awaitMod = parseOptional(SyntaxKind.AwaitKeyword)
        parseExpected(SyntaxKind.OpenParen)
        val afterOpenParen = trailingComments()

        // Track `for ()` error-recovery: when token is `)` right after `(`, report TS1109
        // and skip all the semicolon parsing (matching TypeScript's error recovery).
        var forMissingHeader = false
        val initializer: Node? = when (token) {
            VarKeyword, LetKeyword, ConstKeyword -> {
                disallowIn = true
                val v = parseVariableDeclarationList()
                disallowIn = false
                v
            }
            Semicolon -> null
            CloseParen -> {
                // `for ()` — missing init AND semicolons. Report TS1109 "Expression expected"
                // at the `)` position (matching TypeScript's behavior).
                reportError("Expression expected.", code = 1109)
                forMissingHeader = true
                null
            }
            else -> {
                disallowIn = true
                val e = parseExpression()
                disallowIn = false
                e
            }
        }

        // Capture trailing comments on the initializer (between init and in/of/;)
        // For VariableDeclarationList, the trailing comments are already captured
        // on the last variable declaration's nameTrailingComments, so skip here.
        val afterInit = if (initializer !is VariableDeclarationList) trailingComments() else null

        // `for ()` error recovery: skip straight to CloseParen parsing (no semicolons needed)
        if (forMissingHeader) {
            parseExpected(SyntaxKind.CloseParen)
            val afterCloseParen = trailingComments()
            val body = parseStatement() ?: EmptyStatement()
            return ForStatement(
                initializer = null,
                condition = null,
                incrementor = null,
                statement = body,
                afterKeywordComments = afterKeyword,
                afterOpenParenComments = afterOpenParen,
                afterInitComments = null,
                afterSemicolon1Comments = null,
                afterConditionComments = null,
                afterSemicolon2Comments = null,
                beforeCloseParenComments = null,
                afterCloseParenComments = afterCloseParen,
                syntheticSemicolons = true,
                pos = pos,
                end = getEnd(),
                leadingComments = comments
            )
        }

        if (parseOptional(SyntaxKind.InKeyword)) {
            val afterIn = trailingComments()
            val expr = parseExpression()
            val beforeCloseParen = trailingComments()
            parseExpected(SyntaxKind.CloseParen)
            val afterCloseParen = trailingComments()
            val body = parseStatement() ?: EmptyStatement()
            return ForInStatement(
                initializer = initializer ?: Identifier(""),
                expression = expr,
                statement = body,
                afterKeywordComments = afterKeyword,
                afterOpenParenComments = afterOpenParen,
                afterInitComments = afterInit,
                afterInComments = afterIn,
                beforeCloseParenComments = beforeCloseParen,
                afterCloseParenComments = afterCloseParen,
                pos = pos,
                end = getEnd(),
                leadingComments = comments
            )
        }

        if (parseOptional(SyntaxKind.OfKeyword) || (isIdentifier() && scanner.getTokenValue() == "of" && run { nextToken(); true })) {
            val afterOf = trailingComments()
            val expr = parseAssignmentExpression()
            val beforeCloseParen = trailingComments()
            parseExpected(SyntaxKind.CloseParen)
            val afterCloseParen = trailingComments()
            val body = parseStatement() ?: EmptyStatement()
            return ForOfStatement(
                awaitModifier = awaitMod,
                initializer = initializer ?: Identifier(""),
                expression = expr,
                statement = body,
                afterKeywordComments = afterKeyword,
                afterOpenParenComments = afterOpenParen,
                afterInitComments = afterInit,
                afterOfComments = afterOf,
                beforeCloseParenComments = beforeCloseParen,
                afterCloseParenComments = afterCloseParen,
                pos = pos,
                end = getEnd(),
                leadingComments = comments
            )
        }

        val hasSemicolon1 = parseExpected(SyntaxKind.Semicolon)
        val afterSemicolon1 = trailingComments()
        val condition = if (token != SyntaxKind.Semicolon && token != SyntaxKind.CloseParen) parseExpression() else null
        val afterCondition = if (condition != null) trailingComments() else null
        val hasSemicolon2 = parseExpected(SyntaxKind.Semicolon)
        val afterSemicolon2 = trailingComments()
        val incrementor = if (token != SyntaxKind.CloseParen) parseExpression() else null
        val beforeCloseParen = trailingComments()
        parseExpected(SyntaxKind.CloseParen)
        val afterCloseParen = trailingComments()
        val body = parseStatement() ?: EmptyStatement()
        return ForStatement(
            initializer = initializer,
            condition = condition,
            incrementor = incrementor,
            statement = body,
            afterKeywordComments = afterKeyword,
            afterOpenParenComments = afterOpenParen,
            afterInitComments = afterInit,
            afterSemicolon1Comments = afterSemicolon1,
            afterConditionComments = afterCondition,
            afterSemicolon2Comments = afterSemicolon2,
            beforeCloseParenComments = beforeCloseParen,
            afterCloseParenComments = afterCloseParen,
            syntheticSemicolons = !hasSemicolon1 && !hasSemicolon2,
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
        val afterKeyword = trailingComments()
        val openParenPos = scanner.getTokenPos()
        parseExpected(SyntaxKind.OpenParen)
        val afterOpenParen = trailingComments()
        val expr = parseExpression()
        val beforeCloseParen = trailingComments()
        parseExpectedClosing(SyntaxKind.CloseParen, openParenPos)
        val afterCloseParen = trailingComments()
        val stmt = parseStatement() ?: EmptyStatement()
        return WithStatement(
            expression = expr, statement = stmt,
            afterKeywordComments = afterKeyword,
            afterOpenParenComments = afterOpenParen,
            beforeCloseParenComments = beforeCloseParen,
            afterCloseParenComments = afterCloseParen,
            pos = pos, end = getEnd(), leadingComments = comments,
        )
    }

    private fun parseSwitchStatement(): SwitchStatement {
        val pos = getPos()
        val comments = leadingComments()
        parseExpected(SyntaxKind.SwitchKeyword)
        val afterKeyword = trailingComments()
        parseExpected(SyntaxKind.OpenParen)
        val afterOpenParen = trailingComments()
        val expr = parseExpression()
        val beforeCloseParen = trailingComments()
        parseExpected(SyntaxKind.CloseParen)
        val afterCloseParen = trailingComments()
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
            afterKeywordComments = afterKeyword,
            afterOpenParenComments = afterOpenParen,
            beforeCloseParenComments = beforeCloseParen,
            afterCloseParenComments = afterCloseParen,
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
            val afterCase = trailingComments()
            val expr = parseExpression()
            val afterExpr = trailingComments()
            parseExpected(SyntaxKind.Colon)
            val labelTrailingComments = scanner.consumeTrailingComments()
            val firstStmtStart = getPos() // scanner.getTokenPos() = start of first statement token
            val stmts = parseCaseClauseStatements()
            // Capture trailing comments after the last block's `}` (e.g. `case: { } /*22*/`)
            val stmtsWithTrailing = attachLastBlockTrailingComments(stmts)
            // Single-line if no newline between case clause start and first statement,
            // and no statement is a multiLine block (which must be emitted multi-line)
            val singleLine = stmtsWithTrailing.size == 1 && firstStmtStart > pos &&
                    firstStmtStart <= source.length && !source.substring(pos, firstStmtStart).contains('\n') &&
                    stmtsWithTrailing.none { it is Block && it.multiLine }
            CaseClause(expression = expr, statements = stmtsWithTrailing, singleLine = singleLine,
                afterCaseComments = afterCase, afterExprComments = afterExpr,
                pos = pos, end = getEnd(),
                labelTrailingComments = labelTrailingComments, leadingComments = comments)
        } else {
            parseExpected(SyntaxKind.DefaultKeyword)
            val afterDefault = trailingComments()
            parseExpected(SyntaxKind.Colon)
            val labelTrailingComments = scanner.consumeTrailingComments()
            val firstStmtStart = getPos()
            val stmts = parseCaseClauseStatements()
            val stmtsWithTrailing = attachLastBlockTrailingComments(stmts)
            val singleLine = stmtsWithTrailing.size == 1 && firstStmtStart > pos &&
                    firstStmtStart <= source.length && !source.substring(pos, firstStmtStart).contains('\n') &&
                    stmtsWithTrailing.none { it is Block && it.multiLine }
            DefaultClause(statements = stmtsWithTrailing, singleLine = singleLine,
                afterDefaultComments = afterDefault,
                pos = pos, end = getEnd(),
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

    /**
     * After [parseCaseClauseStatements], captures any same-line trailing comment left in the scanner
     * (e.g. `} /*22*/`) and attaches it to the last [Block] statement via [Block.copy].
     */
    private fun attachLastBlockTrailingComments(stmts: List<Statement>): List<Statement> {
        val trailing = scanner.consumeTrailingComments() ?: return stmts
        val lastBlock = stmts.lastOrNull() as? Block ?: return stmts
        return stmts.dropLast(1) + lastBlock.copy(trailingComments = trailing)
    }

    private fun parseThrowStatement(): ThrowStatement {
        val pos = getPos()
        val comments = leadingComments()
        nextToken()
        val afterKeyword = trailingComments()
        // Per spec: "No LineTerminator here" — if a line break precedes the expression, parse no expression.
        if (scanner.hasPrecedingLineBreak()) {
            // TS1142: Line break not permitted here
            // Report at the position immediately after 'throw' (the newline position), zero-length span
            val newlinePos = scanner.getPrevTokenEnd()
            reportError("Line break not permitted here.", code = 1142, overrideStart = newlinePos, overrideLength = 0)
        }
        val expr = if (!scanner.hasPrecedingLineBreak()) parseExpression() else null
        val preSemi = if (expr != null && !scanner.hasPrecedingLineBreak()) scanner.consumeTrailingComments() else null
        parseSemicolon()
        val trailing = trailingComments()
        return ThrowStatement(expression = expr, afterKeywordComments = afterKeyword, preSemicolonComments = preSemi, pos = pos, end = getEnd(), leadingComments = comments, trailingComments = trailing)
    }

    private fun parseTryStatement(): TryStatement {
        val pos = getPos()
        val comments = leadingComments()
        parseExpected(SyntaxKind.TryKeyword)
        val afterTry = trailingComments()
        val tryBlock = parseBlock()
        val afterTryBlock = scanner.consumeTrailingComments()
        val catchClause = if (token == SyntaxKind.CatchKeyword) parseCatchClause() else null
        val afterCatchBlock = if (catchClause != null) trailingComments() else null
        val hasFinally = parseOptional(SyntaxKind.FinallyKeyword)
        val afterFinally = if (hasFinally) trailingComments() else null
        val finallyBlock = if (hasFinally) parseBlock() else null
        val afterFinallyBlock = if (hasFinally) scanner.consumeTrailingComments() else null
        return TryStatement(
            tryBlock = tryBlock,
            catchClause = catchClause,
            finallyBlock = finallyBlock,
            afterTryComments = afterTry,
            afterTryBlockComments = afterTryBlock,
            afterCatchBlockComments = afterCatchBlock,
            afterFinallyComments = afterFinally,
            afterFinallyBlockComments = afterFinallyBlock,
            pos = pos,
            end = getEnd(),
            leadingComments = comments
        )
    }

    private fun parseCatchClause(): CatchClause {
        val pos = getPos()
        parseExpected(SyntaxKind.CatchKeyword)
        val afterCatch = trailingComments()
        var afterOpenParen: List<Comment>? = null
        var beforeCloseParen: List<Comment>? = null
        var afterCloseParen: List<Comment>? = null
        val varDecl = if (parseOptional(SyntaxKind.OpenParen)) {
            afterOpenParen = trailingComments()
            val name = parseBindingNameOrPattern()
            val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
            val initializer = if (parseOptional(SyntaxKind.Equals)) {
                val initPos = getPos()
                val expr = parseAssignmentExpression()
                reportError("Catch clause variable cannot have an initializer.", code = 1197, overrideStart = initPos, overrideLength = scanner.getPrevTokenEnd() - initPos)
                expr
            } else null
            beforeCloseParen = trailingComments()
            parseExpected(SyntaxKind.CloseParen)
            afterCloseParen = trailingComments()
            VariableDeclaration(name = name, type = type, initializer = initializer)
        } else null
        val block = parseBlock()
        return CatchClause(
            variableDeclaration = varDecl, block = block,
            afterCatchComments = afterCatch,
            afterOpenParenComments = afterOpenParen,
            beforeCloseParenComments = beforeCloseParen,
            afterCloseParenComments = afterCloseParen,
            pos = pos, end = getEnd(),
        )
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
        val afterLabelComments = scanner.consumeTrailingComments()
        parseExpected(SyntaxKind.Colon)
        val afterColonComments = scanner.consumeTrailingComments()
        val stmt = parseStatement() ?: EmptyStatement()
        return LabeledStatement(
            label = label, statement = stmt,
            afterLabelComments = afterLabelComments, afterColonComments = afterColonComments,
            pos = pos, end = getEnd(), leadingComments = comments,
        )
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
        val name = if (isIdentifier()) parseIdentifier() else {
            // Function declarations (statement form) require a name unless marked as
            // `export default` (Default modifier). Emit TS1003 "Identifier expected."
            // at the current position with a 1-char squiggle, mirroring TypeScript.
            if (ModifierFlag.Default !in modifiers && token == SyntaxKind.OpenParen) {
                reportError("Identifier expected.", code = 1003,
                    overrideStart = scanner.getTokenPos(), overrideLength = 1)
            }
            null
        }
        val parsedTypeParams = parseTypeParametersOpt()
        // 17.147 / B5.4: in JS-like files, fall back to JSDoc `@template T`
        // when no TS-level `<T>` was parsed. Mirror of B5.3 for ClassDeclaration.
        val typeParams = parsedTypeParams ?: parseJSDocTemplateTypeParams(comments)
        val rawParams = parseParameterList()
        // 17.140: in JS-like files, bridge JSDoc `@param {primitive} name` tags
        // to parameter types when the parameter is un-annotated.
        val params = applyJSDocParamPrimitiveTypes(rawParams, comments)
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
        decorators: List<Decorator>? = null,
    ): ClassDeclaration {
        val pos = getPos()
        val comments = outerComments ?: leadingComments()
        parseExpected(SyntaxKind.ClassKeyword)
        // `implements` and `extends` always start heritage clauses, never class names
        val name = if (isIdentifier() && token != SyntaxKind.ImplementsKeyword && token != SyntaxKind.ExtendsKeyword) parseIdentifier() else null
        val ltPos = if (token == SyntaxKind.LessThan) getPos() else -1
        val parsedTypeParams = parseTypeParametersOpt()
        if (parsedTypeParams != null && parsedTypeParams.isEmpty() && ltPos >= 0) {
            val gtEnd = scanner.getPrevTokenEnd()
            reportError("Type parameter list cannot be empty.", code = 1098,
                overrideStart = ltPos, overrideLength = gtEnd - ltPos)
        }
        // 17.146 / B5.3: in JS-like files, parse `/** @template T */` from
        // leading comments and use as the class's type parameters when no
        // TS-level `<T>` declaration is present.
        val typeParams = parsedTypeParams ?: parseJSDocTemplateTypeParams(comments)
        val heritage = parseHeritageClauses(isClass = true)
        // Recovery: `declare class foo();` is invalid (no body, has `()`). When the next
        // token is `(` where `{` is expected, emit TS1005 `{` expected and bail out so the
        // outer parser can recover the `();` tail as an expression statement and continue
        // with whatever follows (e.g. a sibling `function foo() {}` declaration).
        if (token == SyntaxKind.OpenParen) {
            val parenPos = getPos()
            reportError("'{' expected.", code = 1005,
                overrideStart = parenPos, overrideLength = 1)
            val trailing = trailingComments()
            return ClassDeclaration(
                name = name, typeParameters = typeParams, heritageClauses = heritage,
                members = emptyList(), modifiers = modifiers, decorators = decorators,
                beforeOpenBraceComments = null, pos = pos, end = getEnd(),
                leadingComments = comments, trailingComments = trailing,
            )
        }
        val beforeOpenBrace = scanner.consumeTrailingComments()
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
            decorators = decorators,
            beforeOpenBraceComments = beforeOpenBrace,
            pos = pos,
            end = getEnd(),
            leadingComments = comments,
            trailingComments = trailing
        )
    }

    private fun parseHeritageClauses(isClass: Boolean = false): List<HeritageClause>? {
        val clauses = mutableListOf<HeritageClause>()
        var hasExtends = false
        var hasImplements = false
        while (token == SyntaxKind.ExtendsKeyword || token == SyntaxKind.ImplementsKeyword) {
            val clauseToken = token
            // 17.173: TS1172 / TS1175 — duplicate `extends` / `implements` clause.
            // Squiggle on the duplicate keyword itself (length 7 / 10).
            if (clauseToken == SyntaxKind.ExtendsKeyword && hasExtends) {
                reportError("'extends' clause already seen.", code = 1172,
                    overrideStart = getPos(), overrideLength = "extends".length)
            }
            if (clauseToken == SyntaxKind.ImplementsKeyword && hasImplements) {
                reportError("'implements' clause already seen.", code = 1175,
                    overrideStart = getPos(), overrideLength = "implements".length)
            }
            // 17.179: TS1176 — interface declarations cannot have `implements`.
            // Squiggle on the `implements` keyword (length 10).
            if (clauseToken == SyntaxKind.ImplementsKeyword && !isClass && !hasImplements) {
                reportError("Interface declaration cannot have 'implements' clause.",
                    code = 1176, overrideStart = getPos(), overrideLength = "implements".length)
            }
            if (clauseToken == SyntaxKind.ExtendsKeyword) hasExtends = true
            if (clauseToken == SyntaxKind.ImplementsKeyword) hasImplements = true
            if (clauseToken == SyntaxKind.ExtendsKeyword && hasImplements) {
                reportError("'extends' clause must precede 'implements' clause.", code = 1173)
            }
            val pos = getPos()
            nextToken()
            val types = mutableListOf<ExpressionWithTypeArguments>()
            do {
                val typeStartPos = getPos()
                val typeNode = parseExpressionWithTypeArguments()
                // TS1174 "Classes can only extend a single class." — for class extending
                // multiple comma-separated types (e.g. `class C extends A, B`). Only fires
                // for classes (interfaces legitimately extend multiple). Position at each
                // extra type after the first.
                if (isClass && clauseToken == SyntaxKind.ExtendsKeyword && types.isNotEmpty()) {
                    val typeEnd = scanner.getPrevTokenEnd()
                    reportError("Classes can only extend a single class.", code = 1174,
                        overrideStart = typeStartPos, overrideLength = typeEnd - typeStartPos)
                }
                types.add(typeNode)
                val commaPos = getPos() // position of potential comma
                if (!parseOptional(SyntaxKind.Comma)) break
                // If comma is followed by `{` (class body), it's a trailing comma error.
                // Report at the comma position (before we consumed it).
                if (token == SyntaxKind.OpenBrace || token == SyntaxKind.ExtendsKeyword ||
                    token == SyntaxKind.ImplementsKeyword || token == SyntaxKind.EndOfFile) {
                    reportError("Trailing comma not allowed.", code = 1009, overrideStart = commaPos, overrideLength = 1)
                    break
                }
            } while (true)
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
        classBodyDepth++
        try {
            val members = mutableListOf<ClassElement>()
            while (token != SyntaxKind.CloseBrace && token != SyntaxKind.EndOfFile) {
                if (token == SyntaxKind.Semicolon) {
                    members.add(SemicolonClassElement(pos = getPos(), end = getEnd()))
                    nextToken()
                    continue
                }
                val member = parseClassMember()
                if (member != null) {
                    members.add(member)
                } else {
                    // parseClassMember returned null (e.g. `{` after modifiers) — exit class body
                    break
                }
            }
            return members
        } finally {
            classBodyDepth--
        }
    }

    private fun parseClassMember(): ClassElement? {
        val pos = getPos()
        val comments = leadingComments()
        val decorators = parseDecorators()
        val modifiers = parseModifiers()

        // TS1434: 'static' keyword appearing as a class member name is unexpected.
        // This happens when 'static' appears twice: `static static foo` or `public static static foo`.
        // After parseModifiers(), if token is still StaticKeyword, the 2nd 'static' is used as name.
        // Only fire if the NEXT token after the 2nd 'static' is on the SAME LINE (no line break),
        // which indicates the 2nd 'static' is part of a member declaration (not standalone).
        if (token == SyntaxKind.StaticKeyword) {
            val nextHasLineBreak = lookAhead {
                scanner.scan() // skip the second 'static'
                scanner.hasPrecedingLineBreak()
            }
            if (!nextHasLineBreak) {
                reportError("Unexpected keyword or identifier.", code = 1434, overrideLength = "static".length)
            }
        }

        if (token == SyntaxKind.ConstructorKeyword ||
            (isIdentifier() && scanner.getTokenValue() == "constructor")
        ) {
            return parseConstructor(modifiers, comments, pos)
        }

        val isStatic = ModifierFlag.Static in modifiers
        val asterisk = parseOptional(SyntaxKind.Asterisk)

        if (!asterisk && (token == SyntaxKind.GetKeyword || (isIdentifier() && scanner.getTokenValue() == "get"))) {
            val result = scanner.lookAhead {
                scanner.scan()
                scanner.getToken() != SyntaxKind.OpenParen && scanner.getToken() != SyntaxKind.Colon &&
                        scanner.getToken() != SyntaxKind.Semicolon && scanner.getToken() != SyntaxKind.Equals &&
                        scanner.getToken() != SyntaxKind.Comma && scanner.getToken() != SyntaxKind.CloseBrace &&
                        scanner.getToken() != SyntaxKind.LessThan // get<T>() is a generic method, not a getter
            }
            if (result) {
                nextToken() // skip 'get'
                return parseGetAccessor(modifiers, comments, pos, decorators)
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
                return parseSetAccessor(modifiers, comments, pos, decorators)
            }
        }

        // static block
        if (isStatic && token == SyntaxKind.OpenBrace) {
            val body = parseBlock()
            return ClassStaticBlockDeclaration(body = body, pos = pos, end = getEnd())
        }

        // Error recovery: `{` after access modifiers (non-static) can't be a valid property name.
        // Return null so the class body exits early and `{}` is parsed as a block statement.
        // Only applies when modifiers were actually consumed (e.g. `public {`).
        // Mirror TypeScript's diagnostics: TS1146 "Declaration expected." at end of modifier
        // (zero-length squiggle, between modifier and `{`), and TS1005 "';' expected." at `{`.
        // The TS1005 emission also pre-empts the parseExpected(CloseBrace) error in
        // parseClassDeclaration (same position → reportError dedup suppresses it).
        if (modifiers.isNotEmpty() && !isStatic && token == SyntaxKind.OpenBrace) {
            val modifierEnd = scanner.getPrevTokenEnd()
            val openBracePos = getPos()
            reportError("Declaration expected.", code = 1146,
                overrideStart = modifierEnd, overrideLength = 0)
            reportError("';' expected.", code = 1005,
                overrideStart = openBracePos, overrideLength = 1)
            return null
        }

        // Index signature: [identifier?: type]: type  (parameter may have optional `?`)
        if (!asterisk && token == SyntaxKind.OpenBracket) {
            val isIndexSig = scanner.lookAhead {
                scanner.scan() // skip [
                if (scanner.getToken() != SyntaxKind.Identifier) return@lookAhead false
                scanner.scan() // skip parameter name
                if (scanner.getToken() == SyntaxKind.Question) scanner.scan() // skip optional ?
                scanner.getToken() == SyntaxKind.Colon
            }
            // Detect index signature with accessibility modifier — emit TS2369 + TS1018.
            // `[public x: T]` etc.
            val isIndexSigWithAccessMod = !isIndexSig && scanner.lookAhead {
                scanner.scan() // skip [
                val mod = scanner.getToken()
                if (mod != SyntaxKind.PublicKeyword && mod != SyntaxKind.PrivateKeyword &&
                    mod != SyntaxKind.ProtectedKeyword) return@lookAhead false
                scanner.scan() // skip modifier
                if (scanner.getToken() != SyntaxKind.Identifier) return@lookAhead false
                scanner.scan() // skip name
                if (scanner.getToken() == SyntaxKind.Question) scanner.scan()
                scanner.getToken() == SyntaxKind.Colon
            }
            // Detect rest parameter index signature: [...identifier...] — TS1017
            val isRestIndexSig = !isIndexSig && !isIndexSigWithAccessMod && scanner.lookAhead {
                scanner.scan() // skip [
                scanner.getToken() == SyntaxKind.DotDotDot
            }
            if (isIndexSig || isRestIndexSig || isIndexSigWithAccessMod) {
                parseExpected(SyntaxKind.OpenBracket)
                val accessModPos = if (isIndexSigWithAccessMod) getPos() else -1
                if (accessModPos >= 0) nextToken() // consume modifier
                val dotDotDotPos = if (token == SyntaxKind.DotDotDot) getPos() else -1
                if (dotDotDotPos >= 0) nextToken() // consume ...
                val paramName = parseIdentifier()
                val questionPos = if (token == SyntaxKind.Question) getPos() else -1
                if (questionPos >= 0) nextToken() // consume ?
                val paramType = if (parseOptional(SyntaxKind.Colon)) parseType() else null
                // B69.2: `[a: T = expr]` — index sig param with initializer.
                // TypeScript emits TS1020 + TS2371 (in place of TS1021).
                val hasInitializer = token == SyntaxKind.Equals
                var initEndForTs2371 = -1
                if (hasInitializer) {
                    nextToken() // consume =
                    parseAssignmentExpression()
                    initEndForTs2371 = scanner.getPrevTokenEnd()
                }
                // TS1025: trailing comma in index signature like `[key: string,]`.
                if (token == SyntaxKind.Comma) {
                    val commaPos = getPos()
                    val nextIsClose = scanner.lookAhead {
                        scanner.scan()
                        scanner.getToken() == SyntaxKind.CloseBracket
                    }
                    if (nextIsClose) {
                        nextToken() // consume trailing ,
                        reportError("An index signature cannot have a trailing comma.", code = 1025,
                            overrideStart = commaPos, overrideLength = 1)
                    }
                }
                parseExpected(SyntaxKind.CloseBracket)
                val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
                parseSemicolon()
                if (accessModPos >= 0) {
                    // TS2369 + TS1018 for `[public x: T]`-style index sigs.
                    // TS2369 squiggle covers `public x: T` — use prevTokenEnd (end of `T`
                    // text before the next-token scan past `]`).
                    val ts2369End = if (paramType != null) {
                        // After parseType + closeBracket+colon+returnType+semicolon, scanner
                        // has advanced way past `T`. Use paramName.end + paramType length.
                        // paramType.end overshoots — instead compute via source-text scan
                        // from accessModPos for the modifier+space+name+colon+space+type.
                        // Simpler: scan source forward for `]` from paramName.pos.
                        var i = paramName.pos
                        while (i < source.length && source[i] != ']') i++
                        i
                    } else paramName.end
                    val ts2369Len = (ts2369End - accessModPos).coerceAtLeast(1)
                    reportError("A parameter property is only allowed in a constructor implementation.",
                        code = 2369, overrideStart = accessModPos, overrideLength = ts2369Len)
                    reportError("An index signature parameter cannot have an accessibility modifier.",
                        code = 1018, overrideStart = paramName.pos,
                        overrideLength = paramName.text.length.coerceAtLeast(1))
                } else if (dotDotDotPos >= 0) {
                    // TS1017: An index signature cannot have a rest parameter.
                    // Suppress TS1021 when TS1017 fires
                    reportError("An index signature cannot have a rest parameter.", code = 1017,
                        overrideStart = dotDotDotPos, overrideLength = 3)
                } else if (questionPos >= 0) {
                    // TS1019: An index signature parameter cannot have a question mark.
                    // Suppress TS1021 when TS1019 fires
                    reportError("An index signature parameter cannot have a question mark.", code = 1019,
                        overrideStart = questionPos, overrideLength = 1)
                } else if (hasInitializer) {
                    // B69.2: TS1020 + TS2371 for index sig param initializer.
                    // Suppress TS1021 — these errors take its place. Both land at the
                    // param name position; emit directly to bypass reportError's same-
                    // position dedup (TypeScript explicitly emits BOTH).
                    reportError("An index signature parameter cannot have an initializer.", code = 1020,
                        overrideStart = paramName.pos, overrideLength = paramName.text.length.coerceAtLeast(1))
                    val ts2371Len = (initEndForTs2371 - paramName.pos).coerceAtLeast(1)
                    val (lineN, charN) = getLineAndCharacterOfPosition(paramName.pos)
                    diagnostics.add(Diagnostic(
                        message = "A parameter initializer is only allowed in a function or constructor implementation.",
                        category = DiagnosticCategory.Error,
                        code = 2371,
                        fileName = fileName,
                        line = lineN,
                        character = charN,
                        start = paramName.pos,
                        length = ts2371Len,
                    ))
                } else if (type == null) {
                    // TS1021: An index signature must have a type annotation.
                    val nodeEnd = scanner.getPrevTokenEnd()
                    reportError("An index signature must have a type annotation.", code = 1021,
                        overrideStart = pos, overrideLength = nodeEnd - pos)
                }
                val param = Parameter(name = paramName, type = paramType)
                return IndexSignature(parameters = listOf(param),
                    type = type, modifiers = modifiers, pos = pos, end = getEnd(),
                    leadingComments = comments)
            }
        }

        val name = parsePropertyName()
        // 17.167: TS1248 — class members cannot have the `const` keyword.
        // `parseModifiers` accepts Const; emit at the member name position
        // with length = name text length when Const is present.
        if (ModifierFlag.Const in modifiers && name is Identifier) {
            reportError(
                "A class member cannot have the 'const' keyword.",
                code = 1248, overrideStart = name.pos, overrideLength = name.text.length,
            )
        }
        val question = parseOptional(SyntaxKind.Question)
        val exclPos = if (token == SyntaxKind.Exclamation) getPos() else -1
        val excl = parseOptional(SyntaxKind.Exclamation)

        return if (token == SyntaxKind.OpenParen || token == SyntaxKind.LessThan) {
            // Method
            val typeParams = parseTypeParametersOpt()
            val rawParams = parseParameterList()
            // 17.140: JSDoc `@param {primitive} name` bridge for class MethodDeclaration.
            val params = applyJSDocParamPrimitiveTypes(rawParams, comments)
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
            var type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
            val init = if (parseOptional(SyntaxKind.Equals)) parseAssignmentExpression() else null
            // 17.204: TS1264 — `class C { p!; }` (definite-assignment `!` without
            // type annotation). Squiggle on the `!` token (length 1).
            if (excl && type == null && exclPos >= 0) {
                reportError(
                    "Declarations with definite assignment assertions must also have type annotations.",
                    code = 1264, overrideStart = exclPos, overrideLength = 1,
                )
            }
            parseSemicolon()
            val trailing = trailingComments()
            // 17.58b: in JS-like files, a missing type annotation on a class property
            // can be supplied by a leading `/** @type {T} */` JSDoc comment.
            var typeFromJSDoc = false
            if (type == null && isJsLikeFile) {
                val jsdocType = parsePropertyTypeFromJSDoc(comments)
                if (jsdocType != null) {
                    type = jsdocType
                    typeFromJSDoc = true
                }
            }
            PropertyDeclaration(
                name = name, type = type, initializer = init, modifiers = modifiers,
                questionToken = question, exclamationToken = excl, decorators = decorators,
                pos = pos, end = getEnd(), leadingComments = comments, trailingComments = trailing,
                typeFromJSDoc = typeFromJSDoc,
            )
        }
    }

    private fun parseConstructor(modifiers: Set<ModifierFlag>, comments: List<Comment>?, pos: Int): Constructor {
        nextToken() // skip 'constructor'
        // Constructors cannot have type parameters. Emit TS1092 when `<...>` is present, and TS1098
        // when the list is empty (e.g. `constructor<>()`).
        if (token == SyntaxKind.LessThan) {
            val ltPos = getPos()
            val typeParams = parseTypeParametersOpt()
            if (typeParams != null) {
                val gtEnd = scanner.getPrevTokenEnd()
                if (typeParams.isEmpty()) {
                    reportError("Type parameter list cannot be empty.", code = 1098,
                        overrideStart = ltPos, overrideLength = gtEnd - ltPos)
                }
                reportError("Type parameters cannot appear on a constructor declaration.",
                    code = 1092, overrideStart = ltPos + 1, overrideLength = 0)
            }
        }
        // B98: in JS files, JSDoc `@template`/`@return` on a CONSTRUCTOR are illegal
        // (TS1092 / TS1093) — TypeScript accepts these tags on other methods, so gating
        // at the constructor parse site auto-excludes them. String-scan the ctor's own
        // leading JSDoc block; purely syntactic (mirrors parseJSDocTemplateTypeParams).
        reportJSDocIllegalCtorTags(comments)
        val rawParams = parseParameterList()
        // 17.140: JSDoc `@param {primitive} name` bridge for Constructor.
        val params = applyJSDocParamPrimitiveTypes(rawParams, comments)
        // 17.181 + 17.182: parameter-property diagnostics for the constructor's
        // parameter list. A "parameter property" is a constructor param with
        // one of the access/readonly modifiers (public/private/protected/readonly).
        // - TS2398 (17.181): `public constructor: string` — name `constructor`
        //   is reserved (conflicts with synthesized class member).
        // - TS1317 (17.182): `public ...rest: string[]` — rest params can't be
        //   parameter properties.
        for (param in params) {
            val isParamProperty = ModifierFlag.Public in param.modifiers ||
                ModifierFlag.Private in param.modifiers ||
                ModifierFlag.Protected in param.modifiers ||
                ModifierFlag.Readonly in param.modifiers
            if (!isParamProperty) continue
            if (param.dotDotDotToken) {
                // `param.end` overshoots by one token (scanner has advanced past
                // the next token's leading trivia + the next token itself).
                // Walk backward through source from `param.end - 1` to find the
                // last non-whitespace character of the parameter's text.
                var endChar = (param.end - 1).coerceAtLeast(param.pos)
                while (endChar > param.pos && source[endChar].isWhitespace()) endChar--
                val lengthRest = (endChar - param.pos).coerceAtLeast(1)
                reportError(
                    "A parameter property cannot be declared using a rest parameter.",
                    code = 1317, overrideStart = param.pos, overrideLength = lengthRest,
                )
                continue
            }
            val nm = param.name
            if (nm is Identifier && nm.text == "constructor") {
                reportError(
                    "'constructor' cannot be used as a parameter property name.",
                    code = 2398, overrideStart = nm.pos, overrideLength = nm.text.length,
                )
            }
        }
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

    /**
     * B98: TS1092 / TS1093 — a JS-file constructor whose leading JSDoc carries a
     * `@template` (type parameter) or `@return`/`@returns {type}` (type annotation) tag.
     * TypeScript rejects these on a constructor specifically. Purely a string-scan of the
     * ctor's own leading `/** */` block; offsets map to absolute source via `comment.pos`.
     * Gated to JS-like files (mirrors [parseJSDocTemplateTypeParams]); .ts files ignore
     * JSDoc tags. Squiggle: TS1092 → the first type-param name; TS1093 → the inner type
     * text inside `{...}`.
     */
    private fun reportJSDocIllegalCtorTags(comments: List<Comment>?) {
        if (!isJsLikeFile || comments.isNullOrEmpty()) return
        for (comment in comments) {
            if (comment.kind != SyntaxKind.MultiLineComment) continue
            val ct = comment.text
            if (!ct.startsWith("/**")) continue
            // TS1092: @template on a constructor — squiggle the first type-param name.
            run {
                val tagIdx = ct.indexOf("@template")
                if (tagIdx < 0) return@run
                val afterTag = if (tagIdx + 9 < ct.length) ct[tagIdx + 9] else ' '
                if (afterTag.isLetterOrDigit() || afterTag == '_') return@run
                var i = tagIdx + 9
                while (i < ct.length && (ct[i] == ' ' || ct[i] == '\t')) i++
                // Skip an optional `{Constraint}` block.
                if (i < ct.length && ct[i] == '{') {
                    var depth = 1; i++
                    while (i < ct.length && depth > 0) { when (ct[i]) { '{' -> depth++; '}' -> depth-- }; if (depth == 0) break; i++ }
                    if (i < ct.length && ct[i] == '}') i++
                    while (i < ct.length && (ct[i] == ' ' || ct[i] == '\t')) i++
                }
                if (i < ct.length && (ct[i].isLetter() || ct[i] == '_' || ct[i] == '$')) {
                    val nameStart = i
                    while (i < ct.length && (ct[i].isLetterOrDigit() || ct[i] == '_' || ct[i] == '$')) i++
                    reportError("Type parameters cannot appear on a constructor declaration.",
                        code = 1092, overrideStart = comment.pos + nameStart, overrideLength = i - nameStart)
                }
            }
            // TS1093: @return/@returns {type} on a constructor — squiggle the inner type text.
            run {
                for (tag in listOf("@returns", "@return")) {
                    val tagIdx = ct.indexOf(tag)
                    if (tagIdx < 0) continue
                    val afterTag = if (tagIdx + tag.length < ct.length) ct[tagIdx + tag.length] else ' '
                    if (afterTag.isLetterOrDigit() || afterTag == '_') continue
                    var i = tagIdx + tag.length
                    while (i < ct.length && (ct[i] == ' ' || ct[i] == '\t')) i++
                    if (i < ct.length && ct[i] == '{') {
                        val innerStart = i + 1
                        var depth = 1; i++
                        while (i < ct.length && depth > 0) { when (ct[i]) { '{' -> depth++; '}' -> depth-- }; if (depth == 0) break; i++ }
                        val innerEnd = i
                        reportError("Type annotation cannot appear on a constructor declaration.",
                            code = 1093, overrideStart = comment.pos + innerStart, overrideLength = (innerEnd - innerStart).coerceAtLeast(1))
                    } else {
                        reportError("Type annotation cannot appear on a constructor declaration.",
                            code = 1093, overrideStart = comment.pos + tagIdx, overrideLength = tag.length)
                    }
                    break
                }
            }
        }
    }

    private fun parseGetAccessor(modifiers: Set<ModifierFlag>, comments: List<Comment>?, pos: Int, decorators: List<Decorator>? = null): GetAccessor {
        val name = parsePropertyName()
        val params = parseParameterList()
        val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
        val body = if (token == SyntaxKind.OpenBrace) parseBlock() else {
            // Getter without a body: `get length(): number;` (ambient) or `get pgF()` (error recovery).
            // Report missing '{' only if the current token is not a semicolon (error recovery case).
            if (token != SyntaxKind.Semicolon) reportErrorAtPrevTokenEnd("'{' expected.")
            parseSemicolon()
            null  // Return null body — Transformer synthesizes empty Block for concrete (non-abstract) classes
        }
        val trailing = trailingComments()
        return GetAccessor(
            name = name,
            parameters = params,
            type = type,
            body = body,
            modifiers = modifiers,
            decorators = decorators,
            pos = pos,
            end = getEnd(),
            leadingComments = comments,
            trailingComments = trailing,
        )
    }

    private fun parseSetAccessor(modifiers: Set<ModifierFlag>, comments: List<Comment>?, pos: Int, decorators: List<Decorator>? = null): SetAccessor {
        val name = parsePropertyName()
        val params = parseParameterList()
        // 17.193: TS1051 — setter cannot have an optional parameter. Squiggle on
        // the `?` token. We can't easily recover the `?` position from the
        // Parameter node (consumed by parseOptional), so search forward in the
        // source from the param name's end for the first `?`.
        if (params.size == 1) {
            val p = params[0]
            if (p.questionToken && p.name is Identifier) {
                val nm = p.name as Identifier
                val searchStart = nm.pos + nm.text.length
                var i = searchStart
                while (i < source.length && source[i] != '?' && source[i] != ':' && source[i] != ')' && source[i] != '=') i++
                if (i < source.length && source[i] == '?') {
                    reportError(
                        "A 'set' accessor cannot have an optional parameter.",
                        code = 1051, overrideStart = i, overrideLength = 1,
                    )
                }
            }
        }
        // Setters cannot have a return type annotation, but parse it for error recovery (preserved in emit).
        val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
        val body = if (token == SyntaxKind.OpenBrace) parseBlock() else {
            // Error recovery: report missing '{' and create empty body
            if (token != SyntaxKind.Semicolon) reportErrorAtPrevTokenEnd("'{' expected.")
            parseSemicolon()
            Block(statements = emptyList(), multiLine = false, pos = -1, end = -1)
        }
        val trailing = trailingComments()
        return SetAccessor(
            name = name,
            parameters = params,
            type = type,
            body = body,
            modifiers = modifiers,
            decorators = decorators,
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
        // Recovery: `interface Foo.I1 { }` is invalid (interface names must be simple
        // identifiers, not qualified names). Consume the `.` and bail out so the outer
        // statement parser can recover the `I1 { }` tail — TypeScript emits the `I1;` +
        // `{ }` block as leftover from the malformed interface header.
        if (token == SyntaxKind.Dot) {
            val dotPos = getPos()
            reportError("'{' expected.", code = 1005,
                overrideStart = dotPos, overrideLength = 1)
            nextToken() // consume `.` so outer parser starts at the qualifier identifier
            // TypeScript also emits TS1434 "Unexpected keyword or identifier."
            // at the identifier-after-dot position. The outer parser will then
            // parse `<ident>;` as an expression statement (which fires TS2304).
            if (isIdentifier()) {
                val identPos = getPos()
                val identLen = scanner.getTokenText().length
                reportError("Unexpected keyword or identifier.", code = 1434,
                    overrideStart = identPos, overrideLength = identLen)
            }
            return InterfaceDeclaration(
                name = name, typeParameters = null, heritageClauses = emptyList(),
                members = emptyList(), modifiers = modifiers, pos = pos, end = getEnd(),
                leadingComments = comments,
            )
        }
        val typeParams = parseTypeParametersOpt()
        val heritage = parseHeritageClauses()
        parseExpected(SyntaxKind.OpenBrace)
        val members = parseInterfaceMembers()
        if (interfaceMembersBailedOnKeyword) {
            interfaceMembersBailedOnKeyword = false
        } else {
            parseExpected(SyntaxKind.CloseBrace)
        }
        return InterfaceDeclaration(
            name = name, typeParameters = typeParams, heritageClauses = heritage,
            members = members, modifiers = modifiers, pos = pos, end = getEnd(), leadingComments = comments
        )
    }

    private fun parseInterfaceMembers(): List<ClassElement> {
        val members = mutableListOf<ClassElement>()
        while (token != SyntaxKind.CloseBrace && token != SyntaxKind.EndOfFile) {
            // Recovery: `var x: T;` / `let x = e;` / `const x = e;` inside an interface
            // body is invalid syntax. Break out so the outer statement parser can recover
            // the remaining content as top-level statements (e.g.
            // `interface Foo<T> { var x: T<>; }` recovers to an empty interface followed
            // by `var x;` at the file scope). Lookahead-gated to skip the case where
            // the keyword is being used as a PROPERTY NAME (`{ const: ...; var: ...; }`):
            // a true var-statement is followed by an identifier, NOT `:` or `(`.
            if (token == SyntaxKind.VarKeyword || token == SyntaxKind.LetKeyword ||
                token == SyntaxKind.ConstKeyword
            ) {
                val looksLikeVarStmt = lookAhead {
                    nextToken()
                    // After the keyword: must NOT be `:` (property name), `(` (method),
                    // `,` / `;` / `}` (lone keyword as property). An identifier or
                    // OpenBracket/OpenBrace (destructuring) means we're starting a var
                    // declaration that doesn't belong here.
                    isIdentifier() || token == SyntaxKind.OpenBracket || token == SyntaxKind.OpenBrace
                }
                if (looksLikeVarStmt) {
                    // B18.1: emit TS1131 at the keyword position (length = keyword text)
                    // so the outer parser doesn't emit a redundant TS1005 from
                    // parseExpected(CloseBrace). The caller sees the flag and skips it.
                    val kw = when (token) {
                        SyntaxKind.VarKeyword -> "var"
                        SyntaxKind.LetKeyword -> "let"
                        else -> "const"
                    }
                    reportError(
                        "Property or signature expected.", code = 1131,
                        overrideStart = getPos(), overrideLength = kw.length,
                    )
                    interfaceMembersBailedOnKeyword = true
                    break
                }
            }
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
            // 17.168: TS1071 — `public` / `private` / `protected` modifier
            // cannot appear on an index signature inside an interface /
            // type literal. The squiggle is on the modifier keyword (length
            // 6/7/9) at the captured `pos`. Only emit for the FIRST present
            // access modifier — TypeScript reports one diagnostic per
            // member.
            val accessKeyword = when {
                ModifierFlag.Public in modifiers -> "public"
                ModifierFlag.Private in modifiers -> "private"
                ModifierFlag.Protected in modifiers -> "protected"
                else -> null
            }
            if (accessKeyword != null) {
                reportError(
                    "'$accessKeyword' modifier cannot appear on an index signature.",
                    code = 1071, overrideStart = pos, overrideLength = accessKeyword.length,
                )
            }
            // Index signature or computed property
            return parseIndexSignatureOrProperty(modifiers, pos)
        }

        if (token == SyntaxKind.OpenParen || token == SyntaxKind.LessThan) {
            // B19.2: Malformed call signature recovery. When `<` is followed by a non-
            // identifier (and non-modifier) token, `parseTypeParametersOpt()` fails
            // speculatively and `parseParameterList()` then errors at the `<` with
            // TS1005 `'('` expected. TypeScript's actual diagnostic for shapes like
            // `<-` inside a type literal is TS1139 "Type parameter declaration
            // expected." at the offending token, followed by TS1109 "Expression
            // expected." at the closing brace position. Detect this by checking the
            // token immediately after `<` before invoking the speculative parser.
            if (token == SyntaxKind.LessThan) {
                val malformedTypeParam = scanner.lookAhead {
                    scanner.scan() // skip `<`
                    val t = scanner.getToken()
                    // Not a valid identifier / modifier / `>` start for type params.
                    !isIdentifierToken(t) && t != SyntaxKind.ConstKeyword &&
                        t != SyntaxKind.InKeyword && t != SyntaxKind.OutKeyword &&
                        t != SyntaxKind.GreaterThan
                }
                if (malformedTypeParam) {
                    nextToken() // consume `<`
                    val tpPos = scanner.getTokenPos()
                    reportError(
                        "Type parameter declaration expected.",
                        code = 1139, overrideStart = tpPos, overrideLength = 1,
                    )
                    // Consume the offending tokens up to but not including the
                    // member terminator (`}` / `;` / `,` / EOF). This prevents
                    // the failed-call-signature payload (e.g. `<-`) from leaking
                    // into the outer statement parser as expression statements.
                    while (token != SyntaxKind.CloseBrace && token != SyntaxKind.Semicolon
                        && token != SyntaxKind.Comma && token != SyntaxKind.EndOfFile
                    ) {
                        nextToken()
                    }
                    // Emit TS1109 at the close-brace position to match TypeScript's
                    // baseline (the failed call-signature triggers an Expression-
                    // expected diagnostic at the position where the parser bails).
                    if (token == SyntaxKind.CloseBrace) {
                        reportError(
                            "Expression expected.",
                            code = 1109, overrideStart = scanner.getTokenPos(), overrideLength = 1,
                        )
                    }
                    return null
                }
            }
            // Call signature
            val typeParams = parseTypeParametersOpt()
            val params = parseParameterList()
            // Call signatures in type literals / interfaces use `(params): retType`,
            // NOT the arrow form `(params) => retType`. If we see `=>`, emit TS1005
            // "':' expected." at the arrow position and consume the arrow + return
            // type so the surrounding parse recovery doesn't cascade.
            val type = if (token == SyntaxKind.EqualsGreaterThan) {
                reportError("':' expected.", code = 1005,
                    overrideStart = scanner.getTokenPos(), overrideLength = 2)
                nextToken() // consume `=>`
                parseType()
            } else if (parseOptional(SyntaxKind.Colon)) parseType() else null
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
                // In type/interface context, accessor bodies are invalid (TS1183)
                // Consume the body to prevent it leaking into outer statement list
                if (token == SyntaxKind.OpenBrace) {
                    val bodyStart = getPos()
                    parseBlock()  // consume and discard the body
                    val bodyEnd = scanner.getPrevTokenEnd()
                    reportError("An implementation cannot be declared in ambient contexts.", 1183, bodyEnd - bodyStart, bodyStart)
                }
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
        // 17.180: TS1246 / TS1247 — type literal / interface property cannot have an initializer.
        // When `=` follows the type annotation, consume the initializer
        // (so subsequent members parse cleanly) and emit the appropriate code at the
        // initializer's first character (matches TypeScript's baseline
        // squiggle position of the value, length 1). The TS1247 wording is used
        // when parsing inside a type literal (`{ ... }` in type position);
        // TS1246 inside an interface body.
        if (token == SyntaxKind.Equals) {
            nextToken()
            val initStart = scanner.getTokenPos()
            parseAssignmentExpression()
            if (inTypeLiteralForErrorWording) {
                reportError(
                    "A type literal property cannot have an initializer.",
                    code = 1247, overrideStart = initStart, overrideLength = 1,
                )
            } else {
                reportError(
                    "An interface property cannot have an initializer.",
                    code = 1246, overrideStart = initStart, overrideLength = 1,
                )
            }
        }
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
        // Could be [key: type]: type  or [K in T (as N)?]: type  or [computed]: type
        val isIndex = scanner.lookAhead {
            scanner.scan() // skip [
            if (!isIdentifierToken(scanner.getToken())) return@lookAhead false
            scanner.scan() // skip name
            if (scanner.getToken() == SyntaxKind.Question) scanner.scan() // skip optional ?
            scanner.getToken() == SyntaxKind.Colon
        }
        // Detect index sig with accessibility modifier — emit TS2369 + TS1018.
        val isIndexWithAccessMod = !isIndex && scanner.lookAhead {
            scanner.scan() // skip [
            val mod = scanner.getToken()
            if (mod != SyntaxKind.PublicKeyword && mod != SyntaxKind.PrivateKeyword &&
                mod != SyntaxKind.ProtectedKeyword) return@lookAhead false
            scanner.scan() // skip modifier
            if (!isIdentifierToken(scanner.getToken())) return@lookAhead false
            scanner.scan() // skip name
            if (scanner.getToken() == SyntaxKind.Question) scanner.scan()
            scanner.getToken() == SyntaxKind.Colon
        }
        // Detect empty index signature: [] — TS1096
        val isEmptyIndexSig = scanner.lookAhead {
            scanner.scan() // skip [
            scanner.getToken() == SyntaxKind.CloseBracket
        }
        // Detect rest parameter index signature: [...identifier...] — TS1017
        val isRestIndexSig = !isIndex && !isEmptyIndexSig && scanner.lookAhead {
            scanner.scan() // skip [
            scanner.getToken() == SyntaxKind.DotDotDot
        }
        // Detect mapped type: [K in T ...] — completely skip it (pure type construct)
        val isMappedType = scanner.lookAhead {
            scanner.scan() // skip [
            if (scanner.getToken() != SyntaxKind.Identifier) return@lookAhead false
            scanner.scan() // skip identifier (type parameter name)
            scanner.getToken() == SyntaxKind.InKeyword
        }
        if (isMappedType) {
            parseExpected(SyntaxKind.OpenBracket)
            nextToken() // consume type parameter name
            parseExpected(SyntaxKind.InKeyword) // consume 'in'
            parseType() // constraint type (e.g., keyof T)
            if (parseOptional(SyntaxKind.AsKeyword)) {
                parseType() // name type (e.g., `${K}Suffix` template literal type)
            }
            parseExpected(SyntaxKind.CloseBracket)
            // Handle optional modifiers after ]: ?, +?, -?
            when (token) {
                SyntaxKind.Question -> nextToken()
                SyntaxKind.Plus, SyntaxKind.Minus -> {
                    nextToken()
                    parseOptional(SyntaxKind.Question)
                }
                else -> {}
            }
            val mappedTypeEnd = scanner.getPrevTokenEnd()
            val hasValueType = parseOptional(SyntaxKind.Colon)
            if (hasValueType) parseType() // value type
            // TS7039: mapped type without an explicit value type implicitly types
            // members as `any`. Fires under noImplicitAny/strict. TypeScript's
            // squiggle covers the OUTER braces (`{[P in K]}`), not just the
            // bracketed part, so scan back from `pos` (start of `[`) to find the
            // enclosing `{` and emit from there.
            if (!hasValueType && noImplicitAny) {
                var braceStart = pos - 1
                while (braceStart > 0 && source[braceStart] != '{') braceStart--
                val sqStart = if (braceStart >= 0 && source[braceStart] == '{') braceStart else pos
                // Scan forward from `mappedTypeEnd` to include the closing `}`.
                var braceEnd = mappedTypeEnd
                while (braceEnd < source.length && source[braceEnd] != '}') braceEnd++
                val sqEnd = if (braceEnd < source.length && source[braceEnd] == '}') braceEnd + 1 else mappedTypeEnd
                reportError(
                    "Mapped object type implicitly has an 'any' template type.",
                    code = 7039, overrideStart = sqStart, overrideLength = (sqEnd - sqStart).coerceAtLeast(1),
                )
            }
            return PropertyDeclaration(
                name = Identifier(text = "", pos = pos, end = pos),
                modifiers = modifiers,
                pos = pos,
                end = getEnd()
            )
        }
        if (isEmptyIndexSig) {
            // Zero-parameter index signature: [] or []: type — TS1096
            parseExpected(SyntaxKind.OpenBracket)
            parseExpected(SyntaxKind.CloseBracket)
            val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
            parseSemicolon() // consume trailing ; if present (extends span to include it)
            val nodeEnd = scanner.getPrevTokenEnd() // end of last-consumed token (after ; or after return type)
            reportError("An index signature must have exactly one parameter.", code = 1096,
                overrideStart = pos, overrideLength = nodeEnd - pos)
            return IndexSignature(parameters = emptyList(), type = type, modifiers = modifiers, pos = pos, end = nodeEnd)
        }
        if (isRestIndexSig) {
            // Rest parameter index signature: [...name(:type)?](:type)? — TS1017
            parseExpected(SyntaxKind.OpenBracket)
            val dotDotDotPos = getPos()
            nextToken() // consume ...
            val paramName = parseIdentifier()
            val paramType = if (parseOptional(SyntaxKind.Colon)) parseType() else null
            parseExpected(SyntaxKind.CloseBracket)
            val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
            parseSemicolon()
            // TS1017: An index signature cannot have a rest parameter.
            reportError("An index signature cannot have a rest parameter.", code = 1017,
                overrideStart = dotDotDotPos, overrideLength = 3)
            // When TS1017 fires, suppress TS1021 (TypeScript doesn't emit both for the same sig)
            val param = Parameter(name = paramName, type = paramType)
            return IndexSignature(parameters = listOf(param), type = type, modifiers = modifiers, pos = pos, end = getEnd())
        }
        if (isIndex || isIndexWithAccessMod) {
            parseExpected(SyntaxKind.OpenBracket)
            val accessModPos = if (isIndexWithAccessMod) getPos() else -1
            if (accessModPos >= 0) nextToken() // consume modifier
            val params = mutableListOf<Parameter>()
            val paramName = parseIdentifier()
            val questionPos = if (token == SyntaxKind.Question) getPos() else -1
            if (questionPos >= 0) nextToken() // consume ?
            parseExpected(SyntaxKind.Colon)
            val paramType = parseType()
            // B69.2: `[a: T = expr]` — index sig param with initializer. TypeScript
            // emits TS1020 (at param name, length 1) + TS2371 (at param name,
            // spanning through the initializer). Consume the initializer so the
            // rest of the parse continues normally.
            val hasInitializer = token == SyntaxKind.Equals
            var initEndForTs2371 = -1
            if (hasInitializer) {
                nextToken() // consume =
                parseAssignmentExpression()
                initEndForTs2371 = scanner.getPrevTokenEnd()
            }
            params.add(Parameter(name = paramName, type = paramType))
            // Parse any additional parameters (invalid — TS1096 for multi-param index signature)
            var hasExtraParams = false
            var hasTrailingComma = false
            while (token == SyntaxKind.Comma) {
                val commaPos = getPos()
                nextToken() // consume ,
                if (token == SyntaxKind.CloseBracket || token == SyntaxKind.EndOfFile) {
                    // TS1025: An index signature cannot have a trailing comma.
                    reportError("An index signature cannot have a trailing comma.", code = 1025,
                        overrideStart = commaPos, overrideLength = 1)
                    hasTrailingComma = true
                    break
                }
                params.add(parseParameter())
                hasExtraParams = true
            }
            if (hasExtraParams) {
                // TS1096: report at the first parameter's name position
                reportError("An index signature must have exactly one parameter.", code = 1096,
                    overrideStart = paramName.pos, overrideLength = paramName.text.length.coerceAtLeast(1))
            }
            parseExpected(SyntaxKind.CloseBracket)
            val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
            parseSemicolon() // consume trailing ; if present (extends span to include it for TS1021)
            // B68.4: Suppress TS1021 when the param type is an invalid keyword
            // (any/boolean/etc.) — the checker emits TS1268 instead, matching TypeScript
            // (TypeScript doesn't double-report TS1021 + TS1268 for the same sig).
            // B292: a NAMED param type (`[p:Purple]` — a TypeReference) is equally
            // TS1268's domain — suppress TS1021 for those too.
            val paramTypeIsInvalidKeyword = (paramType is KeywordTypeNode &&
                paramType.kind !in INDEX_SIG_ALLOWED_PARAM_KEYWORDS) ||
                paramType is TypeReference
            if (accessModPos >= 0) {
                // TS2369 + TS1018 for `[public x: T]`-style index sigs.
                // Scan forward in source from paramName for `]` to compute span end.
                var i = paramName.pos
                while (i < source.length && source[i] != ']') i++
                val ts2369End = i
                val ts2369Len = (ts2369End - accessModPos).coerceAtLeast(1)
                reportError("A parameter property is only allowed in a constructor implementation.",
                    code = 2369, overrideStart = accessModPos, overrideLength = ts2369Len)
                reportError("An index signature parameter cannot have an accessibility modifier.",
                    code = 1018, overrideStart = paramName.pos,
                    overrideLength = paramName.text.length.coerceAtLeast(1))
            } else if (questionPos >= 0) {
                // TS1019: An index signature parameter cannot have a question mark.
                // Suppress TS1021 when TS1019 fires (TypeScript doesn't emit both)
                reportError("An index signature parameter cannot have a question mark.", code = 1019,
                    overrideStart = questionPos, overrideLength = 1)
            } else if (hasInitializer) {
                // B69.2: TS1020 + TS2371 for index sig param initializer. Suppress
                // TS1021 — these errors take its place.
                reportError("An index signature parameter cannot have an initializer.", code = 1020,
                    overrideStart = paramName.pos, overrideLength = paramName.text.length.coerceAtLeast(1))
                val ts2371Len = (initEndForTs2371 - paramName.pos).coerceAtLeast(1)
                reportError(
                    "A parameter initializer is only allowed in a function or constructor implementation.",
                    code = 2371, overrideStart = paramName.pos, overrideLength = ts2371Len)
            } else if (!hasExtraParams && type == null && !paramTypeIsInvalidKeyword) {
                // TS1021: An index signature must have a type annotation.
                val nodeEnd = scanner.getPrevTokenEnd()
                reportError("An index signature must have a type annotation.", code = 1021,
                    overrideStart = pos, overrideLength = nodeEnd - pos)
            }
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
        // If there's a line break between 'type' and the name, ASI applies — not a type alias
        !scanner.hasPrecedingLineBreak() && isIdentifier() && scanner.getToken() != SyntaxKind.Dot
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
        // TS1110 "Type expected." — fires when the type body is missing.
        // Two shapes: (a) `type X` (no `=`) — TS1110 at the position right after the
        // name, then TS1005 from parseExpected(Equals); (b) `type X = ` (with `=` but
        // no body) — TS1110 at the position right after `= `. TypeScript orders
        // TS1110 first (source position) then TS1005.
        //
        // Leading `|` or `&` in `type X = | A | B` is valid (parseType handles them),
        // so don't flag those tokens as "missing type" — treat them as type-starts.
        if (token != SyntaxKind.Equals && token != SyntaxKind.Bar && token != SyntaxKind.Ampersand && !isStartOfType(token)) {
            // Case (a): missing `=`. Emit TS1110 at the position right after the name.
            reportError(
                message = "Type expected.",
                code = 1110,
                overrideStart = scanner.getPrevTokenEnd(),
                overrideLength = 0,
            )
        }
        val eqConsumed = parseExpected(SyntaxKind.Equals)
        if (eqConsumed && token != SyntaxKind.Bar && token != SyntaxKind.Ampersand && !isStartOfType(token)) {
            // Case (b): `=` consumed but no type body. Emit TS1110 at the position right
            // after `=` (TypeScript's "expected type after =" position — past trailing
            // whitespace on the same line in real source, but our scanner uses end-of-`=`
            // which is what TypeScript reports in its baseline). Use `getPrevTokenEnd()`.
            reportError(
                message = "Type expected.",
                code = 1110,
                overrideStart = scanner.getPrevTokenEnd(),
                overrideLength = 0,
            )
        }
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
            // 17.183: TS2452 — enum member cannot have a numeric (or bigint)
            // name. `parsePropertyName` returns NumericLiteralNode for `0` /
            // `1.5` / etc. and BigIntLiteralNode for `0n`. Squiggle on the
            // literal text length.
            when (mName) {
                is NumericLiteralNode -> reportError(
                    "An enum member cannot have a numeric name.",
                    code = 2452, overrideStart = mName.pos, overrideLength = mName.text.length,
                )
                is BigIntLiteralNode -> reportError(
                    "An enum member cannot have a numeric name.",
                    code = 2452, overrideStart = mName.pos, overrideLength = mName.text.length,
                )
                else -> { /* Identifier / StringLiteralNode / ComputedPropertyName ok */ }
            }
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
        val isGlobal = token == SyntaxKind.GlobalKeyword
        val usedModuleKeyword = token == SyntaxKind.ModuleKeyword
        nextToken() // skip namespace/module/global
        // TS1540: using 'module' keyword for namespace (identifier name, not string literal)
        val emitTs1540 = usedModuleKeyword && token != SyntaxKind.StringLiteral
        if (emitTs1540) {
            reportError("A 'namespace' declaration should not be declared using the 'module' keyword. Please use the 'namespace' keyword instead.", code = 1540)
        }
        val name: Expression = if (isGlobal) {
            // `declare global { ... }` — no module name, just the body
            Identifier(text = "global", pos = pos, end = pos + 6)
        } else if (token == SyntaxKind.StringLiteral) {
            parseStringLiteral()
        } else {
            var ident: Expression = parseIdentifier()
            while (parseOptional(SyntaxKind.Dot)) {
                if (emitTs1540) {
                    // Each dotted segment uses the deprecated keyword; emit TS1540 per segment.
                    reportError("A 'namespace' declaration should not be declared using the 'module' keyword. Please use the 'namespace' keyword instead.", code = 1540)
                }
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
        // Also allow keyword tokens (e.g. `import public = require("1")`) — TypeScript parses these
        // as ImportEqualsDeclaration even when the name is a strict-mode reserved word.
        if ((isIdentifier() || isKeyword()) && scanner.lookAhead { scanner.scan(); scanner.getToken() == SyntaxKind.Equals }) {
            val name = parseIdentifier()
            // 17.207: TS2438 — `import string = ...` (reserved primitive type
            // name as import alias). Squiggle on the name (length = name text).
            if (name.text in RESERVED_TYPE_KEYWORD_NAMES) {
                reportError(
                    "Import name cannot be '${name.text}'.",
                    code = 2438, overrideStart = name.pos, overrideLength = name.text.length,
                )
            }
            parseExpected(SyntaxKind.Equals)
            val moduleRef: Node =
                if (token == SyntaxKind.RequireKeyword || (isIdentifier() && scanner.getTokenValue() == "require")) {
                    nextToken()
                    parseExpected(SyntaxKind.OpenParen)
                    val expr = parseExpression()
                    parseExpected(SyntaxKind.CloseParen)
                    ExternalModuleReference(expression = expr, pos = pos, end = getEnd())
                } else {
                    // Emit parser diagnostics for invalid RHS literals (TypeScript parses
                    // `import X = <entityName>` and reports identifier errors at parse time).
                    // The resulting Identifier carries the literal text/rawText so the Transformer
                    // can emit it as a bare ExpressionStatement.
                    when (token) {
                        SyntaxKind.NumericLiteral, SyntaxKind.BigIntLiteral, SyntaxKind.StringLiteral -> {
                            val litStart = getPos()
                            val litToken = token
                            val litText = scanner.getTokenText()
                            val litValue = scanner.getTokenValue()
                            reportError("Identifier expected.", code = 1003,
                                overrideStart = litStart, overrideLength = litText.length)
                            nextToken()
                            val raw = if (litToken == SyntaxKind.StringLiteral || litText != litValue) litText else null
                            Identifier(text = litValue, rawText = raw, pos = litStart, end = getEnd())
                        }
                        SyntaxKind.NullKeyword -> {
                            val litStart = getPos()
                            reportError(
                                "Identifier expected. 'null' is a reserved word that cannot be used here.",
                                code = 1359, overrideStart = litStart, overrideLength = 4
                            )
                            nextToken()
                            Identifier(text = "null", pos = litStart, end = getEnd())
                        }
                        else -> parseQualifiedName()
                    }
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
            parseImportAttributes()
            parseSemicolon()
            val trailing = trailingComments()
            return ImportDeclaration(moduleSpecifier = spec, modifiers = outerModifiers, pos = pos, end = getEnd(), leadingComments = comments, trailingComments = trailing)
        }

        // import clause from "module"
        val clause = parseImportClause(isTypeOnly)
        parseExpected(SyntaxKind.FromKeyword)
        val moduleSpec = parseStringLiteral()
        val assertClause = parseImportAttributes()
        val assertClausePos = lastImportAttributesPos
        parseSemicolon()
        val trailing = trailingComments()
        return ImportDeclaration(
            importClause = clause,
            moduleSpecifier = moduleSpec,
            modifiers = outerModifiers,
            pos = pos,
            end = getEnd(),
            leadingComments = comments,
            assertClause = assertClause,
            assertClausePos = assertClausePos,
            trailingComments = trailing,
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
                    val trailing = trailingComments()
                    // B148: a JS-file `/** @type {T} */ export default <expr>` supplies T as the
                    // export's expected type (excess-prop for an object literal; missing-prop for
                    // a variable/other expr). parsePropertyTypeFromJSDoc returns null when there is
                    // no `@type` comment, so this is inert for ordinary export-defaults.
                    val jsdocType = if (isJsLikeFile) parsePropertyTypeFromJSDoc(comments) else null
                    ExportAssignment(
                        expression = expr,
                        isExportEquals = false,
                        modifiers = modifiers,
                        type = jsdocType,
                        pos = pos,
                        end = getEnd(),
                        leadingComments = comments,
                        trailingComments = trailing,
                    )
                }
            }
        }

        // export =
        if (parseOptional(SyntaxKind.Equals)) {
            val expr = parseAssignmentExpression()
            parseSemicolon()
            // Don't capture trailing comments for `export = X`: under ES module emission this
            // statement is silently dropped (Emitter.emitExportAssignment es-module early return),
            // and emitTrailingCommentsBeforeNewline would then attach them to the prior statement.
            return ExportAssignment(
                expression = expr,
                isExportEquals = true,
                pos = pos,
                end = getEnd(),
                leadingComments = comments,
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
            if (token != SyntaxKind.StringLiteral) {
                reportError("String literal expected.", code = 1141)
            }
            val spec = parseStringLiteral()
            val assertClauseNs = parseImportAttributes()
            val assertClauseNsPos = lastImportAttributesPos
            parseSemicolon()
            return ExportDeclaration(
                exportClause = nsExport,
                moduleSpecifier = spec,
                isTypeOnly = isTypeOnly,
                pos = pos,
                end = getEnd(),
                leadingComments = comments,
                assertClause = assertClauseNs,
                assertClausePos = assertClauseNsPos,
            )
        }

        // export { ... } from? "module"
        if (token == SyntaxKind.OpenBrace) {
            val namedExports = parseNamedExports()
            val moduleSpec = if (parseOptional(SyntaxKind.FromKeyword)) {
                if (token != SyntaxKind.StringLiteral) {
                    reportError("String literal expected.", code = 1141)
                }
                parseStringLiteral()
            } else null
            val assertClauseNamed = if (moduleSpec != null) parseImportAttributes() else null
            val assertClauseNamedPos = if (moduleSpec != null) lastImportAttributesPos else -1
            parseSemicolon()
            return ExportDeclaration(
                exportClause = namedExports,
                moduleSpecifier = moduleSpec,
                isTypeOnly = isTypeOnly,
                pos = pos,
                end = getEnd(),
                leadingComments = comments,
                assertClause = assertClauseNamed,
                assertClausePos = assertClauseNamedPos,
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
            ClassKeyword -> {
                val classDecl = parseClassDeclaration(modifiers, comments)
                // 17.165: TS1211 — `export class { }` (no `default`, no name).
                // Squiggle on the outer `export` keyword captured at this branch's
                // outer `pos`, length=6 (length of "export").
                if (classDecl.name == null) {
                    reportError(
                        "A class declaration without the 'default' modifier must have a name.",
                        code = 1211, overrideStart = pos, overrideLength = 6,
                    )
                }
                classDecl
            }
            InterfaceKeyword -> parseInterfaceDeclaration(modifiers, comments)
            TypeKeyword -> parseTypeAliasDeclaration(modifiers, comments)
            EnumKeyword -> parseEnumDeclaration(modifiers, comments)
            NamespaceKeyword, ModuleKeyword -> parseModuleDeclaration(modifiers, comments)
            DeclareKeyword -> {
                val outerPos = pos  // position of the outer `export` keyword
                val inner = parseDeclareDeclaration(modifiers, comments)
                // For `export declare export = x;`, fix the pos to the outer `export`
                if (inner is ExportAssignment) inner.copy(pos = outerPos) else inner
            }
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
                // Mark the inner ExportAssignment with Export modifier for TS1120 detection
                if (inner is ExportAssignment) {
                    inner.copy(modifiers = inner.modifiers + ModifierFlag.Export, pos = pos)
                } else inner
            }

            // export public/private/protected/static import ... (error: modifiers on import)
            PublicKeyword, PrivateKeyword, ProtectedKeyword, StaticKeyword -> {
                nextToken() // skip the invalid modifier
                if (token == ImportKeyword) parseImportDeclaration(modifiers, comments)
                else parseExpressionStatement()
            }

            else -> parseExpressionStatement()
        }
    }

    private fun parseNamedExports(): NamedExports {
        val pos = getPos()
        parseExpected(SyntaxKind.OpenBrace)
        val elements = mutableListOf<ExportSpecifier>()
        while (token != SyntaxKind.CloseBrace && token != SyntaxKind.EndOfFile) {
            // If we encounter `from` keyword here (before any specifier), it means the `}` was missing.
            // Stop — parseExpected(CloseBrace) will report '}' expected at `from`.
            if (token == SyntaxKind.FromKeyword) break
            elements.add(parseExportSpecifier())
            if (token == SyntaxKind.CloseBrace || token == SyntaxKind.EndOfFile) break
            // If we see `from` keyword after a specifier (no comma before it),
            // report ',' expected at the `from` position (matching TypeScript behavior).
            if (token == SyntaxKind.FromKeyword) {
                parseExpected(SyntaxKind.Comma) // reports ',' expected at current (from) position
                break
            }
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
            // `declare declare ...` — duplicate declare keyword, skip and recurse
            DeclareKeyword -> parseDeclareDeclaration(modifiers, comments)

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
                    // `declare export = x` — export-equals assignment
                    SyntaxKind.Equals -> {
                        nextToken() // skip '='
                        val expr = parseAssignmentExpression()
                        parseSemicolon()
                        val trailing = trailingComments()
                        ExportAssignment(
                            expression = expr,
                            isExportEquals = true,
                            modifiers = mods,
                            pos = pos,
                            end = getEnd(),
                            leadingComments = comments,
                            trailingComments = trailing,
                        )
                    }
                    FunctionKeyword -> parseFunctionDeclarationOrExpression(mods, comments)
                    ClassKeyword -> parseClassDeclaration(mods, comments)
                    InterfaceKeyword -> parseInterfaceDeclaration(mods, comments)
                    TypeKeyword -> parseTypeAliasDeclaration(mods, comments)
                    EnumKeyword -> parseEnumDeclaration(mods, comments)
                    NamespaceKeyword, ModuleKeyword -> parseModuleDeclaration(mods, comments)
                    AbstractKeyword -> { nextToken(); parseClassDeclaration(mods + ModifierFlag.Abstract, comments) }
                    AsyncKeyword -> { nextToken(); parseFunctionDeclarationOrExpression(mods + ModifierFlag.Async, comments) }
                    ImportKeyword -> parseImportDeclaration(mods, comments)
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

    /**
     * Tokens that, when they immediately follow a modifier keyword in a class/interface
     * member, mean the keyword is being used as a member NAME rather than a modifier
     * (e.g. `readonly(): T`, `static = 1`, `public: string`). See [parseModifiers].
     */
    private val MODIFIER_NAME_FOLLOWERS = setOf(
        SyntaxKind.OpenParen, SyntaxKind.LessThan, SyntaxKind.Colon, SyntaxKind.Question,
        SyntaxKind.Equals, SyntaxKind.Semicolon, SyntaxKind.Comma, SyntaxKind.CloseBrace,
        SyntaxKind.Exclamation, SyntaxKind.EndOfFile,
    )

    /**
     * Inside a raw `scanner.lookAhead { }` (e.g. generic-arrow detection), skip an entire
     * template literal the scanner is currently positioned on (token == TemplateHead).
     * A `${ expr }` substitution requires [Scanner.reScanTemplateToken] at the closing `}`
     * to continue as TemplateMiddle/Tail; a plain `scan()` would tokenize that `}` as a
     * CloseBrace and let the following backtick start a runaway template that swallows the
     * surrounding `)` / `=>`. Leaves the scanner positioned ON the closing TemplateTail so
     * the caller's subsequent `scan()` advances past it like any other token.
     */
    private fun skipTemplateInScannerLookahead() {
        if (scanner.getToken() != SyntaxKind.TemplateHead) return
        while (true) {
            scanner.scan() // move into the substitution expression
            var braceDepth = 0
            while (scanner.getToken() != SyntaxKind.EndOfFile) {
                when (scanner.getToken()) {
                    SyntaxKind.OpenBrace -> braceDepth++
                    SyntaxKind.CloseBrace -> if (braceDepth == 0) break else braceDepth--
                    SyntaxKind.TemplateHead -> skipTemplateInScannerLookahead() // nested template
                    else -> {}
                }
                scanner.scan()
            }
            if (scanner.getToken() == SyntaxKind.EndOfFile) return
            // Positioned on the `}` closing the substitution → rescan as middle/tail.
            if (scanner.reScanTemplateToken() == SyntaxKind.TemplateTail) return
            // TemplateMiddle: loop; the next scan() enters the following substitution.
        }
    }

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
                // NOTE: `default` is intentionally NOT a modifier here. parseModifiers() is
                // only called for class members (parseClassMember) and interface/type-literal
                // members (parseTypeMember), and `default` is never a member modifier in either —
                // it is a valid member NAME (e.g. zod's `default(def): this`). `export default`
                // is parsed via parseExportDeclaration / parseDefaultStartedStatement, which set
                // ModifierFlag.Default explicitly. Consuming `default` here desynced the whole
                // class body: `default(...)` became a `default` modifier + a garbage name, so a
                // later `const x = ...` was misread as a class member (spurious TS1248 + cascade).
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
            // A modifier keyword is actually the member NAME when immediately followed by a
            // token that starts a method's params/type-params or terminates the member:
            // `(` `<` `:` `?` `=` `;` `,` `}` `!` or EOF — e.g. `readonly(): T`, `static = 1`,
            // `public: string`, `override?: T`. (get/set are disambiguated earlier; `default`
            // is excluded from the `when` above; `static {` static-blocks use `{`, not in the set.)
            // Only applies on the FIRST keyword (mods empty) — once a real modifier is consumed,
            // a following keyword+punctuator is still its name and handled by the same break.
            if (lookAhead { scanner.scan(); scanner.getToken() } in MODIFIER_NAME_FOLLOWERS) break@loop
            if (mod in mods) break@loop  // duplicate modifier — second occurrence is actually the member name
            // Access modifiers are mutually exclusive: public/private/protected cannot combine.
            // If we already have an access modifier and see another, break — second is the member name.
            val isAccessModifier = mod == ModifierFlag.Public || mod == ModifierFlag.Private || mod == ModifierFlag.Protected
            val hasAccessModifier = ModifierFlag.Public in mods || ModifierFlag.Private in mods || ModifierFlag.Protected in mods
            if (isAccessModifier && hasAccessModifier) break@loop
            // Don't consume an access modifier if the next token (after it) is '}' or EOF —
            // in that case the modifier keyword is being used as a standalone property name.
            if (isAccessModifier) {
                val nextToken = lookAhead { scanner.scan(); scanner.getToken() }
                if (nextToken == SyntaxKind.CloseBrace || nextToken == SyntaxKind.EndOfFile) break@loop
            }
            // ASI: `abstract\nfoo()` in class body — `abstract` is a property name,
            // not a modifier for `foo`. Detect via lookahead: if after consuming the
            // `abstract` keyword the next token has a preceding line break, AND
            // we're parsing a class body (classBodyDepth > 0), treat as property name.
            if (mod == ModifierFlag.Abstract && classBodyDepth > 0) {
                val nextHasLineBreak = lookAhead {
                    scanner.scan()
                    scanner.hasPrecedingLineBreak()
                }
                if (nextHasLineBreak) break@loop
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
        var hasAccess = false
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
            // 17.192: TS1028 — duplicate access modifier on parameter property.
            // `constructor(public public a)` / `constructor(private public a)`
            // emits "Accessibility modifier already seen." on the SECOND
            // access keyword.
            val isAccess = mod == ModifierFlag.Public || mod == ModifierFlag.Private ||
                mod == ModifierFlag.Protected
            if (isAccess && hasAccess) {
                val keyword = when (token) {
                    SyntaxKind.PublicKeyword -> "public"
                    SyntaxKind.PrivateKeyword -> "private"
                    SyntaxKind.ProtectedKeyword -> "protected"
                    else -> ""
                }
                if (keyword.isNotEmpty()) {
                    reportError(
                        "Accessibility modifier already seen.",
                        code = 1028, overrideStart = getPos(), overrideLength = keyword.length,
                    )
                }
            }
            if (isAccess) hasAccess = true
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
            val trailing = scanner.getTrailingComments()
            decorators.add(Decorator(expression = expr, pos = pos, end = getEnd(), trailingComments = trailing))
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

        // Check for `async x => expr`, `async () => expr`, `async (params): Type => expr`,
        // and `async <T>(params) => expr` (generic async arrow)
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
                    // Generic async arrow: async <T>(params) => or async <T>(params): Type =>
                    t == SyntaxKind.LessThan -> {
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
                        if (depth != 0) return@lookAhead false
                        scanner.scan() // skip >
                        if (scanner.getToken() != SyntaxKind.OpenParen) return@lookAhead false
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
                        if (parenDepth != 0) return@lookAhead false
                        scanner.scan() // skip )
                        when (scanner.getToken()) {
                            SyntaxKind.EqualsGreaterThan -> true
                            SyntaxKind.Colon -> {
                                scanner.scan()
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
            // Error recovery: if LHS is a missing identifier (no valid expression start),
            // skip the assignment operator and return just the RHS (e.g. `= fn()` → `fn()`).
            if (expr is Identifier && expr.text.isEmpty()) {
                nextToken() // skip the invalid assignment operator
                return parseAssignmentExpression()
            }
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
                left = AsExpression(expression = left, type = type, tightEnd = scanner.getPrevTokenEnd(), pos = left.pos, end = getEnd())
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
                parseExpressionSuffix(AsExpression(expression = expr, type = type, tightEnd = scanner.getPrevTokenEnd(), pos = expr.pos, end = getEnd()))
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
                // In JSX files, <...> is normally a JSX element. BUT `<Identifier extends <Type>...>`
                // is unambiguously a generic arrow function — JSX uses `<Tag attr={value}/>`. An
                // attribute named `extends` would be a boolean shorthand (no type expression
                // following). Disambiguate by what follows `extends`: an Identifier or type
                // keyword means generic arrow; `/`, `>`, `=` means JSX attribute (e.g.
                // `<T extends/>` or `<T extends={true}/>`).
                if (isJsxFile) {
                    val isGenericArrowInJsx = scanner.lookAhead {
                        scanner.scan() // skip <
                        if (scanner.getToken() != SyntaxKind.Identifier) return@lookAhead false
                        scanner.scan() // skip the first identifier
                        if (scanner.getToken() != SyntaxKind.ExtendsKeyword) return@lookAhead false
                        scanner.scan() // skip `extends`
                        when (scanner.getToken()) {
                            SyntaxKind.Slash, SyntaxKind.GreaterThan, SyntaxKind.Equals -> false
                            SyntaxKind.Identifier, SyntaxKind.NumberKeyword, SyntaxKind.StringKeyword,
                            SyntaxKind.BooleanKeyword, SyntaxKind.SymbolKeyword, SyntaxKind.BigIntKeyword,
                            SyntaxKind.AnyKeyword, SyntaxKind.UnknownKeyword, SyntaxKind.ObjectKeyword,
                            SyntaxKind.NeverKeyword, SyntaxKind.OpenParen, SyntaxKind.OpenBrace,
                            SyntaxKind.OpenBracket, SyntaxKind.TypeOfKeyword -> true
                            else -> false
                        }
                    }
                    if (!isGenericArrowInJsx) {
                        return parseJsxElementOrFragment()
                    }
                    // Fall through to generic-arrow / type-assertion handling below.
                }
                // Could be <TypeParams>() => body (generic arrow) or <Type>expr (type assertion)
                val isGenericArrow = scanner.lookAhead {
                    // Skip past <...> type parameter list.
                    // `>>` is treated as two closing `>` to handle nested generics like <A<B>>.
                    // Track `{}` depth to handle constraints like `<T extends { x: number }>`.
                    scanner.scan() // skip <
                    var depth = 1
                    var braceDepth = 0
                    while (depth > 0 && scanner.getToken() != SyntaxKind.EndOfFile) {
                        when (scanner.getToken()) {
                            SyntaxKind.OpenBrace -> braceDepth++
                            SyntaxKind.CloseBrace -> {
                                if (braceDepth == 0) break
                                braceDepth--
                            }
                            SyntaxKind.LessThan -> if (braceDepth == 0) depth++
                            SyntaxKind.GreaterThan -> if (braceDepth == 0) depth--
                            SyntaxKind.GreaterThanGreaterThan -> if (braceDepth == 0) { depth--; if (depth > 0) depth-- }
                            SyntaxKind.Semicolon -> if (braceDepth == 0) break
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
                                    // A template literal in a param default (`p = `${x}``) must be
                                    // skipped via reScanTemplateToken, else the `}` of `${...}` is
                                    // mis-tokenized and the trailing backtick runs away over the `)`.
                                    SyntaxKind.TemplateHead -> skipTemplateInScannerLookahead()
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
                                        // Scan the return type looking for `=>`. Track depth with
                                        // <>, [], (), and {} (object types like `{ x: K }` are valid
                                        // return types and must not break the scan prematurely).
                                        // `>>` counts as two closing angle brackets.
                                        loop@ while (scanner.getToken() != SyntaxKind.EndOfFile) {
                                            when (scanner.getToken()) {
                                                SyntaxKind.OpenParen, SyntaxKind.OpenBracket,
                                                SyntaxKind.LessThan, SyntaxKind.OpenBrace -> typeDepth++
                                                SyntaxKind.CloseParen, SyntaxKind.CloseBracket,
                                                SyntaxKind.CloseBrace -> {
                                                    if (typeDepth == 0) break@loop else typeDepth--
                                                }
                                                SyntaxKind.GreaterThan -> if (typeDepth > 0) typeDepth--
                                                SyntaxKind.GreaterThanGreaterThan -> {
                                                    if (typeDepth > 0) typeDepth--
                                                    if (typeDepth > 0) typeDepth--
                                                }
                                                SyntaxKind.EqualsGreaterThan -> if (typeDepth == 0) { foundArrow = true; break@loop }
                                                SyntaxKind.Semicolon -> if (typeDepth == 0) break@loop
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
        val hadGreaterThan = parseExpected(SyntaxKind.GreaterThan)
        // If `>` was consumed, `getPrevTokenEnd` points just after it; otherwise the type text
        // ended at the previous token's end (before the missing `>`).
        val headerEnd = scanner.getPrevTokenEnd()
        // `yield` cannot be the argument of a type assertion — TypeScript emits TS1109
        // and uses `;` (empty) for the assertion. Parse `yield ...` as a separate statement.
        val expr = if (token == SyntaxKind.YieldKeyword) {
            reportError("Expression expected.", code = 1109)
            OmittedExpression(pos = pos, end = pos)
        } else parseUnaryExpression()
        return TypeAssertionExpression(
            type = type, expression = expr, headerEnd = headerEnd, pos = pos, end = getEnd(),
        )
    }

    // ── JSX Parsing ─────────────────────────────────────────────────────────

    /**
     * Parses a JSX element, self-closing element, or fragment starting at `<`.
     * Called when `isJsxFile` is true and the current token is LessThan.
     */
    private fun parseJsxElementOrFragment(): Expression {
        val pos = getPos()
        val isOutermostJsx = jsxElementDepth == 0
        jsxElementDepth++
        try {
            return parseJsxElementOrFragmentBody(pos, isOutermostJsx)
        } finally {
            jsxElementDepth--
        }
    }

    private fun parseJsxElementOrFragmentBody(pos: Int, isOutermostJsx: Boolean): Expression {
        // Capture the "full start" — position right after the previous token,
        // including the `<`'s leading trivia. Matches TypeScript's getNodePos()
        // semantics, used for the TS17014 squiggle on unclosed fragments at EOF.
        val fullStart = scanner.getPrevTokenEnd()
        nextToken() // consume <

        // JSX fragment: <> ... </>
        if (token == SyntaxKind.GreaterThan) {
            // Save the position right after the > (before scanner advances)
            val afterGtPos = scanner.getPos()
            nextToken() // consume >
            emitTs17004IfNeeded(pos, isOutermostJsx)
            val children = parseJsxChildren(afterGtPos, null)
            // Detect EOF-after-children (unclosed fragment) under needsJsxFlag.
            // Emit TS17014 at the full-start span + a specialized TS1005
            // ('</' expected.) rather than the bare '<' expected. that
            // parseExpected would otherwise produce.
            if (token == SyntaxKind.EndOfFile && needsJsxFlag) {
                reportError(
                    "JSX fragment has no corresponding closing tag.",
                    code = 17014,
                    overrideStart = fullStart,
                    overrideLength = afterGtPos - fullStart,
                )
                reportError("'</' expected.", code = 1005)
            } else {
                parseExpected(SyntaxKind.LessThan)
                parseExpected(SyntaxKind.Slash)
                parseExpected(SyntaxKind.GreaterThan)
            }
            return JsxFragment(children = children, pos = pos, end = getEnd())
        }

        // Parse tag name
        // Malformed-input recovery: when `<` is immediately followed by `{`,
        // the tag name is missing. Emit TS1003 "Identifier expected." at the
        // `{` position and use an empty-text Identifier as the tag name. Do
        // NOT consume `{` — leave it for parseJsxAttributes to treat as a
        // (malformed) spread attribute `{...expr}`, which will then emit
        // TS1005 "'...' expected." against whatever follows the `{`.
        val tagName = if (token == SyntaxKind.OpenBrace) {
            val emptyTagPos = scanner.getPrevTokenEnd()
            reportError("Identifier expected.", code = 1003)
            Identifier(text = "", pos = emptyTagPos, end = emptyTagPos + 1)
        } else {
            parseJsxTagName()
        }

        // Parse attributes
        val attributes = parseJsxAttributes()

        return if (token == SyntaxKind.Slash) {
            // Self-closing: <Tag attrs/>
            nextToken() // consume /
            parseExpected(SyntaxKind.GreaterThan)
            emitTs17004IfNeeded(pos, isOutermostJsx)
            JsxSelfClosingElement(tagName = tagName, attributes = attributes, pos = pos, end = getEnd())
        } else {
            // Opening tag: <Tag attrs>
            // Save position right after > before scanner advances past it
            val afterGtPos = scanner.getPos()
            parseExpected(SyntaxKind.GreaterThan)
            emitTs17004IfNeeded(pos, isOutermostJsx)
            val openingElement = JsxOpeningElement(tagName = tagName, attributes = attributes, pos = pos, end = getEnd())
            val children = parseJsxChildren(afterGtPos, jsxTagNameToString(tagName))
            val closingPos = getPos()
            // Parse </tagname> — but detect the missing-closing-tag-at-EOF shape and
            // emit TS17008 + a specialized TS1005 ('</' expected.) rather than the bare
            // '<' expected. that parseExpected would otherwise produce.
            if (token == SyntaxKind.EndOfFile) {
                val tagNameStr = jsxTagNameToString(tagName)
                reportError(
                    "JSX element '$tagNameStr' has no corresponding closing tag.",
                    code = 17008,
                    overrideStart = tagName.pos,
                    // Empty tag-name (parser-recovered for `<{...`) yields a
                    // zero-length squiggle to match TypeScript's baseline; named
                    // tags get the full tag-name span.
                    overrideLength = jsxTagNameLength(tagName),
                )
                reportError("'</' expected.", code = 1005)
                // Synthetic close with empty-name Identifier so JSX emit renders `</>`
                // (matches TypeScript's unclosed-element recovery emission).
                val syntheticName = Identifier(text = "", pos = closingPos, end = closingPos)
                val syntheticClose = JsxClosingElement(tagName = syntheticName, pos = closingPos, end = closingPos)
                JsxElement(openingElement = openingElement, children = children, closingElement = syntheticClose, pos = pos, end = getEnd())
            } else {
                parseExpected(SyntaxKind.LessThan)
                parseExpected(SyntaxKind.Slash)
                val closingTagName = parseJsxTagName()
                parseExpected(SyntaxKind.GreaterThan)
                val closingElement = JsxClosingElement(tagName = closingTagName, pos = closingPos, end = getEnd())
                JsxElement(openingElement = openingElement, children = children, closingElement = closingElement, pos = pos, end = getEnd())
            }
        }
    }

    /**
     * Length of the source span covered by a JSX tag name, for diagnostic squiggles.
     * Mirrors `jsxTagNameToString`'s recursive shape.
     */
    private fun jsxTagNameLength(tagName: Expression): Int = when (tagName) {
        is Identifier -> tagName.text.length
        is PropertyAccessExpression -> jsxTagNameLength(tagName.expression) + 1 + tagName.name.text.length
        else -> 0
    }

    /**
     * Emits TS17004 "Cannot use JSX unless the '--jsx' flag is provided." at the opening
     * tag/fragment span. Called after the opening `>` (or `/>`) has been consumed so
     * `scanner.getPrevTokenEnd()` gives the span end. Only fires at the outermost JSX
     * element to avoid duplicate diagnostics per nested child.
     */
    private fun emitTs17004IfNeeded(openPos: Int, isOutermostJsx: Boolean) {
        if (!needsJsxFlag || !isOutermostJsx) return
        val end = scanner.getPrevTokenEnd()
        val length = (end - openPos).coerceAtLeast(1)
        reportError(
            "Cannot use JSX unless the '--jsx' flag is provided.",
            code = 17004,
            overrideStart = openPos,
            overrideLength = length,
        )
    }

    /**
     * Parses a JSX tag name. In JSX, even keywords like `const`, `extends`, etc. are valid tag names.
     * Also handles qualified names like `Foo.Bar` and skips optional type arguments `<T>`.
     */
    private fun parseJsxTagName(): Expression {
        val pos = getPos()
        // Any identifier or keyword is valid as a JSX tag name
        val text = scanner.getTokenValue()
        val id = Identifier(text = text, pos = pos, end = getEnd())
        nextToken()
        // Handle qualified name: Foo.Bar.Baz
        val tagName: Expression = if (token == SyntaxKind.Dot) {
            var expr: Expression = id
            while (token == SyntaxKind.Dot) {
                nextToken() // consume .
                val rightPos = getPos()
                val rightText = scanner.getTokenValue()
                val right = Identifier(text = rightText, pos = rightPos, end = getEnd())
                nextToken()
                expr = PropertyAccessExpression(expression = expr, name = right, pos = pos, end = getEnd())
            }
            expr
        } else {
            id
        }
        // Skip optional type arguments: <T>, <'bar'>, <T extends X>, etc.
        // These are TypeScript-specific and get stripped during transformation.
        if (token == SyntaxKind.LessThan) {
            // Try to skip the type argument list
            val skipped = scanner.tryScan {
                scanner.scan() // consume <
                var depth = 1
                while (depth > 0 && scanner.getToken() != SyntaxKind.EndOfFile) {
                    when (scanner.getToken()) {
                        SyntaxKind.LessThan -> depth++
                        SyntaxKind.GreaterThan -> depth--
                        SyntaxKind.GreaterThanGreaterThan -> { depth--; if (depth > 0) depth-- }
                        else -> {}
                    }
                    if (depth > 0) scanner.scan()
                }
                if (depth == 0) {
                    scanner.scan() // consume >
                    true
                } else null
            }
            if (skipped == true) {
                // Update our parser token to what the scanner now has
                token = scanner.getToken()
            }
        }
        return tagName
    }

    private fun jsxTagNameToString(tagName: Expression): String = when (tagName) {
        is Identifier -> tagName.text
        is PropertyAccessExpression -> "${jsxTagNameToString(tagName.expression)}.${tagName.name.text}"
        else -> ""
    }

    /**
     * Parses JSX attributes until `/>` or `>`.
     */
    private fun parseJsxAttributes(): List<Node> {
        val attributes = mutableListOf<Node>()
        while (token != SyntaxKind.Slash && token != SyntaxKind.GreaterThan &&
               token != SyntaxKind.EndOfFile) {
            val attr = parseJsxAttribute()
            if (attr != null) attributes.add(attr)
        }
        return attributes
    }

    /**
     * Parses a single JSX attribute: `name="value"`, `name={expr}`, `name`, or `{...spread}`.
     */
    private fun parseJsxAttribute(): Node? {
        val pos = getPos()
        // Spread attribute: {...expr}
        if (token == SyntaxKind.OpenBrace) {
            nextToken() // consume {
            if (parseExpected(SyntaxKind.DotDotDot)) {
                val expr = parseAssignmentExpression()
                parseExpected(SyntaxKind.CloseBrace)
                return JsxSpreadAttribute(expression = expr, pos = pos, end = getEnd())
            }
            // Recovery: parseExpected emitted TS1005 "'...' expected." but didn't
            // consume. Skip tokens until we reach `}`, `/`, `>`, or EOF — do NOT
            // attempt parseAssignmentExpression on whatever follows (it may consume
            // tokens that legitimately close the opening tag, causing JS-emit drift).
            while (token != SyntaxKind.CloseBrace && token != SyntaxKind.Slash &&
                   token != SyntaxKind.GreaterThan && token != SyntaxKind.EndOfFile) {
                nextToken()
            }
            // Consume `}` if present (the spread block close).
            if (token == SyntaxKind.CloseBrace) nextToken()
            val dummyEnd = getEnd()
            return JsxSpreadAttribute(
                expression = Identifier(text = "", pos = pos, end = dummyEnd),
                pos = pos, end = dummyEnd,
            )
        }
        // Named attribute
        val name = scanner.getTokenValue()
        nextToken() // consume attribute name/keyword

        return if (token == SyntaxKind.Equals) {
            nextToken() // consume =
            val value: Node = when (token) {
                SyntaxKind.StringLiteral -> {
                    val strPos = getPos()
                    val strText = scanner.getTokenValue()
                    val rawText = scanner.getTokenText().let {
                        if (it.length >= 2) it.substring(1, it.length - 1) else it
                    }
                    val str = StringLiteralNode(text = strText, rawText = rawText, pos = strPos, end = getEnd())
                    nextToken()
                    str
                }
                SyntaxKind.OpenBrace -> {
                    val bracePos = getPos()
                    nextToken() // consume {
                    val expr = if (token == SyntaxKind.CloseBrace) null else parseAssignmentExpression()
                    parseExpected(SyntaxKind.CloseBrace)
                    JsxExpressionContainer(expression = expr, pos = bracePos, end = getEnd())
                }
                else -> {
                    // error recovery — skip token
                    val dummyPos = getPos()
                    nextToken()
                    StringLiteralNode(text = "", pos = dummyPos, end = getEnd())
                }
            }
            JsxAttribute(name = name, value = value, pos = pos, end = getEnd())
        } else {
            // Boolean attribute (no = value)
            JsxAttribute(name = name, value = null, pos = pos, end = getEnd())
        }
    }

    /**
     * Parses JSX children content until the matching closing tag.
     * [contentStartPos] is the position in source right after the opening `>`.
     */
    private fun parseJsxChildren(contentStartPos: Int, parentTagName: String?): List<Node> {
        val children = mutableListOf<Node>()
        // Reset scanner to the position right after the opening >, then enter JSX text mode
        scanner.resetToPosition(contentStartPos)

        while (true) {
            // Scan raw JSX text content until < or {
            val textStart = scanner.getPos()
            val rawText = scanner.scanJsxText()
            if (rawText.isNotEmpty()) {
                children.add(JsxText(text = rawText, pos = textStart, end = textStart + rawText.length))
            }
            // Now the scanner is positioned at < or { (or EOF).
            // Call nextToken() to actually consume that character as a token.
            nextToken()

            when (token) {
                SyntaxKind.LessThan -> {
                    // Look ahead: is it </tag> (closing tag) or <tag> (child element)?
                    val isClosing = lookAhead {
                        scanner.scan()
                        scanner.getToken() == SyntaxKind.Slash
                    }
                    if (isClosing) {
                        // Closing tag — stop parsing children; caller will parse </tag>
                        break
                    }
                    // Child JSX element or fragment
                    val child = parseJsxElementOrFragment()
                    children.add(child as Node)
                    // After parsing a child, the scanner scanned one token past the closing >.
                    // We need to resume JSX text scanning from right after that closing >.
                    // scanner.getPrevTokenEnd() gives the position right after the > before the
                    // scanner scanned the NEXT token.
                    scanner.resetToPosition(scanner.getPrevTokenEnd())
                }
                SyntaxKind.OpenBrace -> {
                    // JSX expression container: {expr}
                    val exprPos = getPos()
                    nextToken() // consume content of {
                    val expr = if (token == SyntaxKind.CloseBrace) null else parseAssignmentExpression()
                    parseExpected(SyntaxKind.CloseBrace)
                    children.add(JsxExpressionContainer(expression = expr, pos = exprPos, end = getEnd()))
                    // Re-enter JSX text mode from right after the closing }
                    // getPrevTokenEnd() gives position right after } before next token was scanned
                    scanner.resetToPosition(scanner.getPrevTokenEnd())
                }
                SyntaxKind.EndOfFile -> break
                else -> break
            }
        }
        return children
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
        val expr = when (token) {
            NewKeyword -> parseNewExpression()
            SuperKeyword -> {
                val pos = getPos(); nextToken()
                val superExpr = Identifier(text = "super", pos = pos, end = getEnd())
                // TypeScript requires super to be followed by `.`, `[`, `(`, or `<` (type args).
                // If the next token is none of these, emit TS1034 at the position of the
                // expected follower (1-char span) and insert a missing property name for
                // TypeScript-compatible error recovery (emits `super.`).
                if (token != Dot && token != OpenBracket && token != OpenParen && token != LessThan) {
                    val nextPos = getPos()
                    reportError(
                        "'super' must be followed by an argument list or member access.",
                        code = 1034,
                        overrideStart = nextPos,
                        overrideLength = 1,
                    )
                    val missingName = Identifier(text = "", pos = nextPos, end = nextPos)
                    PropertyAccessExpression(expression = superExpr, name = missingName, pos = pos, end = getPos())
                } else {
                    superExpr
                }
            }

            ImportKeyword -> {
                if (scanner.lookAhead { scanner.scan(); scanner.getToken() == OpenParen }) {
                    // import(...)
                    val pos = getPos()
                    nextToken()
                    parseExpected(OpenParen)
                    val arg = parseAssignmentExpression()
                    if (token == Comma) {
                        val commaPos = getPos()
                        nextToken()
                        // Trailing comma `import(spec,)` — TS1009. Second-arg (options) is
                        // TypeScript 5.3+; the trailing-comma-before-`)` form is still an error.
                        if (token == CloseParen) {
                            reportError("Trailing comma not allowed.", code = 1009,
                                overrideStart = commaPos, overrideLength = 1)
                        }
                    }
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
            // Arrow functions have lower precedence than calls. When the `(` is on a new
            // line after an unwrapped arrow function, treat it as a separate statement.
            // e.g.: `() => {}\n() => {}` → two statements (TypeScript ASI behavior).
            // Wrapped arrow functions like `(() => {})(x)` are fine (result is Parens).
            if (result is ArrowFunction && token == OpenParen && scanner.hasPrecedingLineBreak()) break
            result = when (token) {
                Dot -> {
                    val newLineBefore = scanner.hasPrecedingLineBreak()
                    // B41.2: Capture leading comments on the dot token BEFORE consuming
                    // it — these are comments between the expression and the dot (often
                    // after a newline for multi-line property access like
                    // `expr\n  /* comment */ .toString()`). Without this capture, the
                    // comments would be lost when scanner.scan() resets leadingComments
                    // for the next token (the property name).
                    val dotLeadingCommentsList = if (newLineBefore) leadingComments() else null
                    // Capture trailing line comments that terminated the line BEFORE the
                    // newline preceding the dot (e.g. `arr // should error\n  .filter`).
                    // These are NOT leading-of-dot (because there's no newline between
                    // the previous expression and the comment), but they MUST be preserved
                    // before the newline in the output.
                    // Skip when `result` already captured them via its own trailingComments
                    // (CallExpression in chained-call position uses `callTrailing` for this).
                    val expressionTrailingLineComments = if (newLineBefore && result.trailingComments.isNullOrEmpty()) {
                        scanner.getTrailingComments()
                            ?.filter { !it.hasPrecedingNewLine && it.text.startsWith("//") && it.hasTrailingNewLine }
                            ?.takeIf { it.isNotEmpty() }
                    } else null
                    nextToken()
                    val afterDotPos = scanner.getPrevTokenEnd() // position right after dot (before trivia of next token)
                    val newLineAfterDot = scanner.hasPrecedingLineBreak()
                    // After `.`, only consume if the token is a valid property name.
                    // Non-name tokens (e.g. `}`) are left for the enclosing block to consume.
                    // Error recovery (TypeScript-compatible): newline after dot + reserved keyword +
                    // next token is identifier/keyword → the keyword starts a new statement.
                    // TypeScript error position rules:
                    //   - If next token starts a new statement (e.g. `var`, `let`, identifier after newline)
                    //     → report at afterDotPos (right after the dot, on the same line)
                    //   - If next token closes enclosing context (e.g. `}`, EOF)
                    //     → report at the next token's position (getPos())
                    val name = when {
                        newLineAfterDot && isKeyword() && !isIdentifier() &&
                                lookAhead { nextToken(); isIdentifier() || isKeyword() } -> {
                            // Statement-starting keyword (var, let, function, etc.) → report at afterDotPos
                            reportError("Identifier expected.", code = 1003, overrideStart = afterDotPos, overrideLength = 0)
                            Identifier(text = "", pos = afterDotPos, end = afterDotPos)
                        }
                        isIdentifier() || isKeyword() -> parseIdentifierName()
                        // 16.0: When the next token closes the enclosing context (`}`, EOF, `)`, `]`)
                        // and there's a newline after the dot, TypeScript reports at the closing
                        // token's position with length 1 (e.g. `bar.\n}` → at the `}`).
                        newLineAfterDot && (token == SyntaxKind.CloseBrace || token == SyntaxKind.EndOfFile ||
                                token == SyntaxKind.CloseParen || token == SyntaxKind.CloseBracket) -> {
                            val closePos = getPos()
                            reportError("Identifier expected.", code = 1003, overrideStart = closePos, overrideLength = 1)
                            Identifier(text = "", pos = afterDotPos, end = afterDotPos)
                        }
                        else -> {
                            // Closing token or non-keyword token on same line →
                            // TypeScript reports at the position right after the dot (afterDotPos).
                            reportError("Identifier expected.", code = 1003, overrideStart = afterDotPos, overrideLength = 0)
                            Identifier(text = "", pos = afterDotPos, end = afterDotPos)
                        }
                    }
                    // Merge captured dot-leading comments into the name's leadingComments.
                    // For newLineBefore=true, the emitter places these comments BEFORE the dot
                    // (matching TypeScript's `expr\n  /* comment */ .toString()` form).
                    val finalName = if (dotLeadingCommentsList != null && name is Identifier) {
                        val mergedLeading = (dotLeadingCommentsList + (name.leadingComments ?: emptyList())).ifEmpty { null }
                        name.copy(leadingComments = mergedLeading)
                    } else name
                    PropertyAccessExpression(expression = result, name = finalName, newLineBefore = newLineBefore, newLineAfterDot = newLineAfterDot, expressionTrailingLineComments = expressionTrailingLineComments, pos = result.pos, end = getEnd())
                }

                OpenBracket -> {
                    nextToken()
                    val arg = if (token == SyntaxKind.CloseBracket) {
                        // TS1011: empty element access a[] is invalid
                        // Report at position right after `[` (prevTokenEnd), length 0
                        reportError("An element access expression should take an argument.", code = 1011, overrideLength = 0, overrideStart = scanner.getPrevTokenEnd())
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
                    val innerComments = lastCallInnerComments
                    // Only capture trailing comments on the call when the chain continues
                    // (next token is `.` or `[`). Otherwise leave them for the enclosing
                    // statement to capture as preSemicolonComments / trailingComments.
                    val callTrailing = if (token == Dot || token == OpenBracket) trailingComments() else null
                    CallExpression(expression = result, arguments = args, innerComments = innerComments, pos = result.pos, end = getEnd(), trailingComments = callTrailing)
                }

                LessThan, LessThanLessThan -> {
                    // Try type arguments for call/tagged-template/instantiation — wrap in tryScan so if no `(` or
                    // template follows, scanner is restored to before `<` (fixing `i < 10` in for-loop).
                    // `LessThanLessThan` is also accepted: `foo<<T>(x:T)=>R>(args)` rescans `<<` to `<` inside
                    // `tryParseTypeArguments`. If the rescan path fails to produce a viable type-arg list, the
                    // outer tryScan restores scanner state so `<<` remains available for binary-expression parsing.
                    val typeArgsStart = getPos()
                    var typeArgsEnd = -1
                    val callExpr: Expression? = scanner.tryScan {
                        val typeArgs = tryParseTypeArguments()
                        if (typeArgs != null) typeArgsEnd = scanner.getPrevTokenEnd()
                        when {
                            typeArgs != null && token == SyntaxKind.OpenParen -> {
                                val args = parseArgumentList()
                                CallExpression(
                                    expression = result,
                                    typeArguments = typeArgs,
                                    arguments = args,
                                    innerComments = lastCallInnerComments,
                                    pos = result.pos,
                                    end = getEnd()
                                )
                            }
                            typeArgs != null && (token == SyntaxKind.NoSubstitutionTemplateLiteral || token == SyntaxKind.TemplateHead) -> {
                                val template = parseTemplateLiteral(isTagged = true)
                                TaggedTemplateExpression(
                                    tag = result,
                                    typeArguments = typeArgs,
                                    template = template,
                                    pos = result.pos,
                                    end = getEnd()
                                )
                            }
                            // Instantiation expression: expr<Type> followed by a token that
                            // cannot start a binary expression (so it's type args, not comparison).
                            // Type arguments are dropped. Wrap in parens (TypeScript emits (expr))
                            // UNLESS the next token continues the expression (., ?., ), ]) — those
                            // either provide their own grouping or feed into a member access.
                            // Exception: when followed by `.` or `?.`, wrap in parens if the
                            // expression contains optional chaining — the `<T>` ends the chain scope,
                            // so `a?.b<T>.c` must emit `(a?.b).c`, not `a?.b.c`.
                            typeArgs != null && canFollowTypeArgumentsInExpression() -> {
                                when (token) {
                                    SyntaxKind.CloseParen, SyntaxKind.CloseBracket -> result
                                    SyntaxKind.Dot, SyntaxKind.QuestionDot -> {
                                        // B23.1: `?.` after instantiation expr may be an optional CALL
                                        // (`a<b>?.()`) or optional INDEX (`a<b>?.[i]`) — both valid.
                                        // Only `?.IDENTIFIER` (optional property access) is rejected
                                        // with TS1477. Peek past `?.` to distinguish.
                                        val isOptionalCallOrIndex = token == SyntaxKind.QuestionDot && scanner.lookAhead {
                                            scanner.scan() // skip `?.`
                                            val next = scanner.getToken()
                                            next == SyntaxKind.OpenParen || next == SyntaxKind.OpenBracket
                                        }
                                        if (isOptionalCallOrIndex) {
                                            // Wrap `a<b>` in a synthetic ParenthesizedExpression so
                                            // downlevel optional-chain emit captures it in a temp var
                                            // (non-trivial chain LHS). `instantiationEnd` tags this as
                                            // synthetic so the transformer can drop the paren when no
                                            // downlevel is needed (ES2020+). Continue the postfix loop
                                            // to consume `?.()` / `?.[i]`.
                                            ParenthesizedExpression(
                                                expression = result,
                                                instantiationEnd = typeArgsEnd,
                                                pos = result.pos,
                                                end = getEnd(),
                                            )
                                        } else {
                                            // TS1477: an instantiation expression cannot be followed by a property access.
                                            reportError(
                                                "An instantiation expression cannot be followed by a property access.",
                                                code = 1477,
                                                overrideStart = typeArgsStart,
                                                overrideLength = typeArgsEnd - typeArgsStart,
                                            )
                                            if (expressionHasOptionalChain(result))
                                                // 17.44: tag synthetic-from-instantiation paren with the type-args
                                                // end position so the checker can emit TS2532 with the correct
                                                // squiggle covering `expr<T>` (not including the trailing `.`).
                                                ParenthesizedExpression(
                                                    expression = result,
                                                    instantiationEnd = typeArgsEnd,
                                                    pos = result.pos,
                                                    end = getEnd(),
                                                )
                                            else result
                                        }
                                    }
                                    else -> ParenthesizedExpression(
                                        expression = result,
                                        // Tag this synthetic paren as originating from an
                                        // instantiation expression — checker uses this to
                                        // emit TS2364 when the paren appears as the LHS of
                                        // an assignment (`obj.fn<T> = ...`).
                                        instantiationEnd = typeArgsEnd,
                                        pos = result.pos,
                                        end = getEnd()
                                    )
                                }
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
                                // TS1011: empty element access a?.[] is invalid
                                // Report at position right after `[` (prevTokenEnd), length 0
                                reportError("An element access expression should take an argument.", code = 1011, overrideLength = 0, overrideStart = scanner.getPrevTokenEnd())
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
                                innerComments = lastCallInnerComments,
                                pos = result.pos,
                                end = getEnd()
                            )
                        }

                        // `a?.<T>(args)` — optional call with explicit type arguments.
                        // Try to parse type arguments; if followed by `(`, it's a generic
                        // optional call. Type arguments are erased in JS emit. If the
                        // tryScan fails to find `(`, fall through to property-access
                        // recovery (the `<` becomes garbage handled by parseIdentifierName).
                        LessThan -> {
                            val callExpr: Expression? = scanner.tryScan {
                                val typeArgs = tryParseTypeArguments()
                                if (typeArgs != null && token == SyntaxKind.OpenParen) {
                                    val args = parseArgumentList()
                                    CallExpression(
                                        expression = result,
                                        typeArguments = typeArgs,
                                        arguments = args,
                                        questionDotToken = true,
                                        innerComments = lastCallInnerComments,
                                        pos = result.pos,
                                        end = getEnd(),
                                    )
                                } else null
                            }
                            if (callExpr != null) {
                                callExpr
                            } else {
                                // tryScan restored scanner; re-sync parser token, fall
                                // through to property-access recovery.
                                token = scanner.getToken()
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
                    val template = parseTemplateLiteral(isTagged = true)
                    // 17.185: TS2796 — tagging a template literal with another
                    // template literal is almost always a missing-comma error
                    // (e.g. inside an array literal). Squiggle on the tag (the
                    // first template literal). Length covers the literal text.
                    if (result is NoSubstitutionTemplateLiteralNode || result is TemplateExpression) {
                        val tagLen = (template.pos - result.pos).coerceAtLeast(1).let { len ->
                            // Trim trailing whitespace (the tag's own end + leading
                            // trivia of the second template).
                            var e = result.pos + len - 1
                            while (e > result.pos && source[e].isWhitespace()) e--
                            (e - result.pos + 1).coerceAtLeast(1)
                        }
                        reportError(
                            "It is likely that you are missing a comma to separate these two template expressions. They form a tagged template expression which cannot be invoked.",
                            code = 2796, overrideStart = result.pos, overrideLength = tagLen,
                        )
                    }
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
        return result
    }

    private fun parseNewExpression(): Expression {
        val pos = getPos()
        parseExpected(NewKeyword)
        if (token == Dot) {
            nextToken()
            val name = parseIdentifier()
            // 17.157: TS17012 — only `new.target` is a valid meta-property
            if (name.text != "target") {
                reportError(
                    "'${name.text}' is not a valid meta-property for keyword 'new'. Did you mean 'target'?",
                    code = 17012,
                    overrideStart = name.pos,
                    overrideLength = name.text.length,
                )
            }
            return MetaProperty(keywordToken = NewKeyword, name = name, pos = pos, end = getEnd())
        }
        // `new <T>Expr` — TypeScript parses `<T>` as leading type arguments (not a type assertion)
        // and keeps them in JS output. Try to parse them before the constructor expression.
        val leadingTypeArgs = if (token == LessThan) tryParseTypeArguments() else null
        // For `new`, the constructor expression allows member access (. and []) and nested `new`,
        // but NOT function calls. parseCallAndAccess would greedily consume `()` and turn
        // `new Foo()` into `new (Foo())` — use parseMemberAccessOnly instead.
        // Handle nested `new` (e.g. `new new Date`) by recursing.
        val baseExpr = if (token == NewKeyword) parseNewExpression() else parsePrimaryExpression()
        val expr = parseMemberAccessOnly(baseExpr)
        // Only parse trailing type args if we didn't find leading ones (e.g. `new Foo<T>()`)
        val typeArgs = if (leadingTypeArgs == null) tryParseTypeArguments() else null
        val args = if (token == OpenParen) parseArgumentList() else null
        val innerComments = if (args != null) lastCallInnerComments else null
        return NewExpression(expression = expr, typeArguments = typeArgs, leadingTypeArguments = leadingTypeArgs, arguments = args, innerComments = innerComments, pos = pos, end = getEnd())
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
                    val name = if (isIdentifier() || isKeyword()) parseIdentifierName()
                               else { reportError("Identifier expected.", code = 1003); Identifier(text = "", pos = getPos(), end = getPos()) }
                    PropertyAccessExpression(expression = result, name = name, newLineBefore = newLineBefore, newLineAfterDot = newLineAfterDot, pos = result.pos, end = getEnd())
                }
                OpenBracket -> {
                    nextToken()
                    val arg = if (token == SyntaxKind.CloseBracket) {
                        // TS1011: empty element access a[] is invalid
                        // Report at position right after `[` (prevTokenEnd), length 0
                        reportError("An element access expression should take an argument.", code = 1011, overrideLength = 0, overrideStart = scanner.getPrevTokenEnd())
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
                val text = scanner.getTokenValue()
                val hasExponent = scanner.didBigIntHaveExponent()
                val hasFraction = scanner.didBigIntHaveFraction()
                val emptyKind = scanner.getEmptyDigitLiteralKind()
                val trailingSeps = scanner.getNumericTrailingSeparatorPositions()
                val doubleSeps = scanner.getNumericDoubleSeparatorPositions()
                val missingExpDigitsPos = scanner.getNumericMissingExponentDigitsPos()
                val identFollow = scanner.getNumericIdentifierFollow()
                val litLen = text.length
                nextToken()
                if (missingExpDigitsPos >= 0) {
                    reportError("Digit expected.", code = 1124,
                        overrideStart = missingExpDigitsPos, overrideLength = 0)
                }
                if (identFollow != null && identFollow.first != missingExpDigitsPos) {
                    reportError("An identifier or keyword cannot immediately follow a numeric literal.",
                        code = 1351, overrideStart = identFollow.first, overrideLength = identFollow.second)
                }
                when {
                    emptyKind == "binary" -> reportError("Binary digit expected.", code = 1177,
                        overrideStart = pos + litLen - 1, overrideLength = 0)
                    emptyKind == "octal" -> reportError("Octal digit expected.", code = 1178,
                        overrideStart = pos + litLen - 1, overrideLength = 0)
                    emptyKind == "hex" -> reportError("Hexadecimal digit expected.", code = 1125,
                        overrideStart = pos + litLen - 1, overrideLength = 0)
                    hasExponent -> reportError("A bigint literal cannot use exponential notation.", code = 1352,
                        overrideStart = pos, overrideLength = litLen)
                    hasFraction -> reportError("A bigint literal must be an integer.", code = 1353,
                        overrideStart = pos, overrideLength = litLen)
                }
                for (sp in trailingSeps) {
                    reportError("Numeric separators are not allowed here.", code = 6188,
                        overrideStart = sp, overrideLength = 1)
                }
                for (sp in doubleSeps) {
                    reportError("Multiple consecutive numeric separators are not permitted.", code = 6189,
                        overrideStart = sp, overrideLength = 1)
                }
                BigIntLiteralNode(
                    text = text,
                    pos = pos,
                    end = getEnd()
                )
            }

            StringLiteral -> parseStringLiteral()
            NoSubstitutionTemplateLiteral -> {
                val text = scanner.getTokenValue(); val unterminated = scanner.isTokenUnterminated()
                emitStringEscapeErrors()
                nextToken()
                if (unterminated) {
                    // TS1160 "Unterminated template literal." at the position right
                    // after the unterminated text (where the closing backtick was expected).
                    reportError("Unterminated template literal.", code = 1160,
                        overrideStart = scanner.getPrevTokenEnd(), overrideLength = 0)
                }
                NoSubstitutionTemplateLiteralNode(
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
                    if (scanner.isTokenUnterminated()) {
                        // TS1161 "Unterminated regular expression literal." — squiggle on the
                        // opening `/` (length 1).
                        reportError("Unterminated regular expression literal.", code = 1161,
                            overrideStart = pos, overrideLength = 1)
                    }
                    val text = scanner.getTokenText(); nextToken()
                    RegularExpressionLiteralNode(text = text, pos = pos, end = getEnd())
                } else {
                    Identifier(text = scanner.getTokenText(), pos = pos, end = getEnd()).also { nextToken() }
                }
            }

            OpenParen -> {
                // 17.140b: in JS-like files, `/** @type {T} */ (expr)` is a JSDoc
                // type cast — the inner expression's type is overridden by T.
                // Capture jsdoc cast BEFORE parsing so we can attach to the
                // resulting ParenthesizedExpression. Skip when the result is an
                // ArrowFunction (parens form arrow params, not a cast target).
                val jsdocCastType = if (isJsLikeFile && comments != null)
                    parsePropertyTypeFromJSDoc(comments) else null
                val parenResult = parseParenthesizedOrArrow()
                if (jsdocCastType != null && parenResult is ParenthesizedExpression) {
                    parenResult.copy(jsdocCastType = jsdocCastType)
                } else parenResult
            }
            OpenBracket -> parseArrayLiteral()
            OpenBrace -> parseObjectLiteral()
            FunctionKeyword -> parseFunctionExpression()
            ClassKeyword -> parseClassExpression()
            At -> {
                // @decorator class C {} in expression position — TS1206 "Decorators are
                // not valid here." but we still parse so downstream checks continue.
                val atPos = getPos()
                val decorators = parseDecorators()
                if (token == SyntaxKind.ClassKeyword) {
                    reportError("Decorators are not valid here.", code = 1206,
                        overrideStart = atPos, overrideLength = 1)
                    parseClassExpression().copy(decorators = decorators)
                } else {
                    parseIdentifier()
                }
            }
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

            else -> {
                // In expression context, report "Expression expected" (TS1109) not "Identifier expected" (TS1003)
                if (isIdentifier() || isKeyword()) {
                    parseIdentifier()
                } else if (token == SyntaxKind.Unknown) {
                    // Unknown token = invalid character (e.g. `\` from an incomplete unicode escape).
                    // Report TS1127 "Invalid character." but do NOT consume the token —
                    // parseStatements' safety mechanism will skip it and discard this "statement".
                    reportError("Invalid character.", code = 1127, overrideLength = 0)
                    Identifier(text = "", pos = pos, end = getEnd())
                } else {
                    // At EOF, position the error at the end of the previous token
                    // (not at the start of the virtual next line)
                    if (token == SyntaxKind.EndOfFile) {
                        reportError("Expression expected.", code = 1109, overrideStart = scanner.getPrevTokenEnd(), overrideLength = 0)
                    } else if (token == SyntaxKind.Equals) {
                        // TS2809 destructuring-assignment hint: when `=` appears in statement-expression
                        // context, it often means the user wrote `{a, b} = fn()` without parens around
                        // the destructuring pattern. Check whether the immediately-preceding non-trivia
                        // character is `}` to detect this shape.
                        val text = scanner.getSourceText()
                        var i = scanner.getTokenPos() - 1
                        while (i >= 0 && text[i] in " \t\r\n") i--
                        if (i >= 0 && text[i] == '}') {
                            reportError(
                                "Declaration or statement expected. This '=' follows a block of statements, so if you intended to write a destructuring assignment, you might need to wrap the whole assignment in parentheses.",
                                code = 2809,
                            )
                        } else {
                            reportError("Expression expected.", code = 1109)
                        }
                    } else {
                        reportError("Expression expected.", code = 1109)
                    }
                    Identifier(text = "", pos = pos, end = getEnd())
                }
            }
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
                                SyntaxKind.OpenParen, SyntaxKind.OpenBracket, SyntaxKind.LessThan,
                                SyntaxKind.OpenBrace -> typeDepth++
                                SyntaxKind.CloseParen, SyntaxKind.CloseBracket -> {
                                    if (typeDepth == 0) break@loop else typeDepth--
                                }
                                SyntaxKind.CloseBrace -> if (typeDepth == 0) break@loop else typeDepth--
                                SyntaxKind.GreaterThan -> if (typeDepth > 0) typeDepth--
                                SyntaxKind.EqualsGreaterThan -> if (typeDepth == 0) { foundArrow = true; break@loop }
                                SyntaxKind.Semicolon, SyntaxKind.Colon -> if (typeDepth == 0) break@loop
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
        // Capture comments on new lines before ')' (e.g. `//close`, `/*3*/` in multi-line paren).
        val beforeCloseParen = leadingComments()
        parseExpected(SyntaxKind.CloseParen)
        // Capture same-line trailing comments after ')' (e.g. `/*4*/` in `(expr)/*4*/`).
        val afterCloseParen = scanner.consumeTrailingComments()?.ifEmpty { null }
        val exprWithComments = if (!innerTrailing.isNullOrEmpty()) expr.withTrailingComments(innerTrailing) else expr
        return ParenthesizedExpression(
            expression = exprWithComments,
            beforeCloseParenComments = beforeCloseParen,
            afterCloseParenComments = afterCloseParen,
            pos = pos,
            end = getEnd(),
        )
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
        // TS1200: Line terminator not permitted before arrow. Emitted at the `=>` token
        // (length 2) when a line break separates the parameter list from `=>`.
        if (token == SyntaxKind.EqualsGreaterThan && scanner.hasPrecedingLineBreak()) {
            reportError(
                "Line terminator not permitted before arrow.",
                code = 1200, overrideLength = 2,
            )
        }
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
        // Per-element post-comma comments: postCommaPerElem[i] holds same-line comments that
        // appeared after element[i]'s comma (e.g. `elem, // comment\n`). These should be emitted
        // AFTER the comma rather than before it (unlike normal element trailing comments).
        val postCommaPerElem = mutableListOf<List<Comment>?>()
        var hasTrailingComma = false
        while (token != SyntaxKind.CloseBracket && token != SyntaxKind.EndOfFile) {
            if (token == SyntaxKind.Comma) {
                elements.add(OmittedExpression(pos = getPos(), end = getPos()))
                // No post-comma comments for omitted elements; add null placeholder to keep alignment.
                postCommaPerElem.add(null)
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
                // Capture same-line comments that appeared AFTER the comma but BEFORE the next line.
                // These must be emitted after the comma (e.g. `elem, // comment\n`), not before it.
                // For trailing-comma cases (next token is `]`), these are captured later as
                // closingComments / node.trailingComments via the hasTrailingComma branch below.
                val postCommaTrailing = if (!hasTrailingComma) scanner.consumeTrailingComments() else null
                postCommaPerElem.add(postCommaTrailing)
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
        // Only populate postCommaComments if there's at least one non-null entry
        val postCommaComments = postCommaPerElem.takeIf { list -> list.any { it != null } }
        return ArrayLiteralExpression(
            elements = elements,
            multiLine = multiLine,
            hasTrailingComma = hasTrailingComma,
            pos = pos,
            end = getEnd(),
            trailingComments = closingComments,
            openBracketComments = openBracketComments,
            closeBracketPos = closeBracketPos,
            postCommaComments = postCommaComments,
        )
    }

    private fun parseObjectLiteral(): ObjectLiteralExpression {
        val pos = getPos()
        parseExpected(SyntaxKind.OpenBrace)
        // multiLine: true when there's a line break right after the opening `{`
        // (same as TypeScript's scanner.hasPrecedingLineBreak() after parsing `{`)
        val multiLineAfterOpen = scanner.hasPrecedingLineBreak()
        val properties = mutableListOf<Node>()
        var hasTrailingComma = false
        var hadSemicolonRecovery = false
        while (token != SyntaxKind.CloseBrace && token != SyntaxKind.EndOfFile) {
            // Skip extra commas (error recovery for double commas like `{ x: 0,, }`)
            if (token == SyntaxKind.Comma) {
                // TS1136 "Property assignment expected." — emitted at the extra comma.
                reportError("Property assignment expected.", code = 1136,
                    overrideStart = getPos(), overrideLength = 1)
                nextToken()
                // If closing brace follows, treat this as a trailing comma
                if (token == SyntaxKind.CloseBrace) hasTrailingComma = true
                continue
            }
            properties.add(parseObjectLiteralElement())
            // Object literals use commas, NOT semicolons, as property separators.
            // Semicolons are errors — TypeScript reports ',' expected at each `;`.
            val hadComma = parseOptional(SyntaxKind.Comma)
            if (!hadComma && token == SyntaxKind.Semicolon) {
                // Error: TypeScript reports ',' expected at the `;` position, but still
                // consumes the semicolon to allow error recovery and parse remaining properties.
                parseExpected(SyntaxKind.Comma) // reports ',' expected at `;`
                nextToken() // consume the semicolon for recovery
                // Capture any same-line trailing comments
                val postSemiTrailing = scanner.getTrailingComments()
                if (postSemiTrailing != null && properties.isNotEmpty()) {
                    properties[properties.size - 1] = withTrailingComments(properties.last(), postSemiTrailing)
                }
                hasTrailingComma = false
                hadSemicolonRecovery = true
            } else if (hadComma) {
                // Capture any same-line trailing comments that appeared after the comma
                val postCommaTrailing = scanner.getTrailingComments()
                if (postCommaTrailing != null && properties.isNotEmpty()) {
                    val last = properties.last()
                    properties[properties.size - 1] = withTrailingComments(last, postCommaTrailing)
                }
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
        // When we had semicolon error recovery and reached EOF (no closing `}`), TypeScript
        // does NOT emit '}'  expected — the semicolon terminated the expression context.
        if (hadSemicolonRecovery && token == SyntaxKind.EndOfFile) {
            // Silently pop the openTokenStack without emitting an error
            if (openTokenStack.isNotEmpty()) openTokenStack.removeAt(openTokenStack.lastIndex)
        } else {
            parseExpected(SyntaxKind.CloseBrace)
        }
        // Use multiLineAfterOpen (line break after `{`) as the multiLine flag.
        // Fallback: also treat as multiLine if the close brace is on a different source line
        // than the open brace AND the difference isn't entirely from within string literals.
        // TypeScript uses hasPrecedingLineBreak() after `{` — we use the same.
        val multiLine = multiLineAfterOpen
        return ObjectLiteralExpression(properties = properties, multiLine = multiLine, hasTrailingComma = hasTrailingComma, pos = pos, end = getEnd(), trailingComments = closingComments, closeBracePos = closeBracePos)
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
            // Capture any same-line trailing comment after the spread expression (e.g. `...x // comment`)
            val spreadTrailing = scanner.consumeTrailingComments()
            return SpreadAssignment(expression = spreadExpr, pos = pos, end = getEnd(), leadingComments = comments, trailingComments = spreadTrailing)
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
                // Error recovery: create empty body when missing, to match TypeScript's output
                val body = if (token == SyntaxKind.OpenBrace) parseBlock()
                    else { reportErrorAtPrevTokenEnd("'{' expected."); Block(statements = emptyList(), multiLine = false, pos = -1, end = -1) }
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
        }

        if (!asterisk && (isIdentifier() && scanner.getTokenValue() == "set")) {
            val isSet = lookAhead { nextToken(); isPropertyNameToken() }
            if (isSet) {
                nextToken()
                val name = parsePropertyName()
                val params = parseParameterList()
                // Error recovery: create empty body when missing, to match TypeScript's output
                val body = if (token == SyntaxKind.OpenBrace) parseBlock()
                    else { reportErrorAtPrevTokenEnd("'{' expected."); Block(statements = emptyList(), multiLine = false, pos = -1, end = -1) }
                val trailing = trailingComments()
                return SetAccessor(name = name, parameters = params, body = body, modifiers = modifiers, pos = pos, end = getEnd(), leadingComments = comments, trailingComments = trailing)
            }
        }

        val name = parsePropertyName()

        // 17.158: TS1162 — object literal members cannot be declared optional.
        // The `?` is still consumed (so downstream parsing stays the same), but a
        // diagnostic with squiggle on the `?` token is emitted.
        if (token == SyntaxKind.Question) {
            val qPos = scanner.getTokenPos()
            nextToken()
            reportError(
                "An object member cannot be declared optional.",
                code = 1162,
                overrideStart = qPos,
                overrideLength = 1,
            )
        }

        // Method shorthand: foo() { ... }  or *foo() { ... } or async foo() { ... }
        if (asterisk || token == SyntaxKind.OpenParen || token == SyntaxKind.LessThan) {
            val typeParams = parseTypeParametersOpt()
            val params = parseParameterList()
            val returnType = if (parseOptional(SyntaxKind.Colon)) parseType() else null
            val body = if (token == SyntaxKind.OpenBrace) parseBlock()
                       else { parseExpected(SyntaxKind.OpenBrace); null }
            val trailingComments = scanner.getTrailingComments()
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
                leadingComments = comments,
                trailingComments = trailingComments
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
            return PropertyAssignment(name = name, initializer = valueWithComments, modifiers = modifiers, pos = pos, end = getEnd(), leadingComments = comments)
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
        val rawParams = parseParameterList()
        // 17.140: JSDoc `@param {primitive} name` bridge for FunctionExpression.
        val params = applyJSDocParamPrimitiveTypes(rawParams, comments)
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
        val ltPos = if (token == SyntaxKind.LessThan) getPos() else -1
        val typeParams = parseTypeParametersOpt()
        if (typeParams != null && typeParams.isEmpty() && ltPos >= 0) {
            val gtEnd = scanner.getPrevTokenEnd()
            reportError("Type parameter list cannot be empty.", code = 1098,
                overrideStart = ltPos, overrideLength = gtEnd - ltPos)
        }
        val heritage = parseHeritageClauses(isClass = true)
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
        // TypeScript only parses a yield operand if:
        // 1. There's an asterisk (yield* always has an operand), OR
        // 2. No preceding line break AND the next token can start an expression
        // This prevents `[yield]` from trying to parse `]` as a yield operand.
        val expr = if (
            !scanner.hasPrecedingLineBreak() && (asterisk || isStartOfExpression())
        ) parseAssignmentExpression() else null
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

    private fun parseTemplateLiteral(isTagged: Boolean = false): Expression {
        if (token == SyntaxKind.NoSubstitutionTemplateLiteral) {
            val text = scanner.getTokenValue()
            val unterminated = scanner.isTokenUnterminated()
            val pos = getPos()
            emitStringEscapeErrors(suppressOctalIllegal = isTagged)
            nextToken()
            if (unterminated) {
                // TS1160 "Unterminated template literal." at the position right after
                // the unterminated text (where the closing backtick was expected).
                reportError("Unterminated template literal.", code = 1160,
                    overrideStart = scanner.getPrevTokenEnd(), overrideLength = 0)
            }
            return NoSubstitutionTemplateLiteralNode(text = text, isUnterminated = unterminated, pos = pos, end = getEnd())
        }
        return parseTemplateExpression(isTagged)
    }

    private fun parseTemplateExpression(isTagged: Boolean = false): TemplateExpression {
        val pos = getPos()
        val headText = scanner.getTokenValue()
        emitStringEscapeErrors(suppressOctalIllegal = isTagged) // emit errors from template head before consuming it
        nextToken() // consume template head
        val head = StringLiteralNode(text = headText, pos = pos, end = getEnd())
        val spans = mutableListOf<TemplateSpan>()
        var isUnterminated = false
        while (token != SyntaxKind.EndOfFile) {
            val spanPos = getPos()
            val expr = parseExpression()
            // After expression, rescan to get template middle or tail
            val literalKind = scanner.reScanTemplateToken()
            val literalText = scanner.getTokenValue()
            val litPos = getPos()
            emitStringEscapeErrors(suppressOctalIllegal = isTagged) // emit errors from middle/tail before consuming it
            nextToken()
            val literal: Node = if (literalKind == SyntaxKind.TemplateTail) {
                StringLiteralNode(text = literalText, pos = litPos, end = getEnd())
            } else {
                StringLiteralNode(text = literalText, pos = litPos, end = getEnd())
            }
            spans.add(TemplateSpan(expression = expr, literal = literal, pos = spanPos, end = getEnd()))
            if (literalKind == SyntaxKind.TemplateTail) break
            // After a TemplateMiddle, if we're now at EOF there's an unclosed `${` at the end.
            // TypeScript emits TS1109 "Expression expected." with a zero-length span at the
            // position right after the `${` — i.e. where the next expression should have been.
            if (token == SyntaxKind.EndOfFile) {
                reportError("Expression expected.", code = 1109,
                    overrideStart = scanner.getPrevTokenEnd(), overrideLength = 0)
                // TS1005 "'}' expected." at the final EOF position — TypeScript also flags
                // the unterminated template substitution with a brace-expected diagnostic.
                reportError("'}' expected.", code = 1005,
                    overrideStart = scanner.getTokenPos(), overrideLength = 0)
                isUnterminated = true
                break
            }
        }
        // If loop exited immediately (no spans) due to EOF after TemplateHead, the `${` is unclosed.
        // Emit TS1109 at the position right after `${`, matching TypeScript's behavior.
        if (spans.isEmpty() && token == SyntaxKind.EndOfFile) {
            reportError("Expression expected.", code = 1109,
                overrideStart = scanner.getTokenPos(), overrideLength = 0)
            isUnterminated = true
        }
        return TemplateExpression(head = head, templateSpans = spans, isUnterminated = isUnterminated, pos = pos, end = getEnd())
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
                result = AsExpression(expression = result, type = type, tightEnd = scanner.getPrevTokenEnd(), pos = result.pos, end = getEnd())
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

    /** Inner comments captured from an empty argument list `(/*comments*/)`. Reset each call. */
    private var lastCallInnerComments: List<Comment>? = null

    private fun parseArgumentList(): List<Expression> {
        lastCallInnerComments = null
        parseExpected(SyntaxKind.OpenParen)
        val args = mutableListOf<Expression>()
        while (token != SyntaxKind.CloseParen && token != SyntaxKind.EndOfFile) {
            if (token == SyntaxKind.Comma) {
                // Missing argument slot e.g. foo(a,,b) — create OmittedExpression
                reportError("Argument expression expected.", code = 1135)
                args.add(OmittedExpression(pos = getPos(), end = getPos()))
                nextToken()
                continue
            }
            // Closing brace encountered inside argument list — error recovery.
            // TypeScript reports TS1135 "Argument expression expected." and breaks.
            if (token == SyntaxKind.CloseBrace) {
                reportError("Argument expression expected.", code = 1135)
                break
            }
            // Capture inline comments before each argument (e.g. /*label*/ before string arg).
            // Comments on the same line as `(` or `,` are classified as trailingComments by the
            // scanner (no preceding line break), while comments on new lines are leadingComments.
            // Both sets must be combined for shapes like `f(  // c1\n  // c2\n  arg)` where
            // `// c1` is a same-line trailing of `(` and `// c2` is a leading of the arg.
            val inlineArgComments = scanner.getTrailingComments()
            val ownLineArgComments = leadingComments()
            val argComments = when {
                inlineArgComments == null -> ownLineArgComments
                ownLineArgComments == null -> inlineArgComments
                else -> inlineArgComments + ownLineArgComments
            }
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
                // Capture trailing same-line comment before comma/close-paren (e.g. `arg // comment`)
                val argTrailing = scanner.consumeTrailingComments()
                var argExpr = if (argComments != null) expr.withLeadingComments(argComments) else expr
                if (argTrailing != null) argExpr = argExpr.withTrailingComments(argTrailing)
                args.add(argExpr)
            }
            if (!parseOptional(SyntaxKind.Comma)) break
        }
        // For empty argument lists, capture any comments between `(` and `)`.
        if (args.isEmpty()) {
            // Same-line trailing comments (e.g. `a(/*1*/)`) are in trailingComments;
            // comments with preceding newlines (e.g. `a(\n  /*first*/\n)`) are in leadingComments.
            val inlineComments = scanner.consumeTrailingComments()
            val blockComments = leadingComments()
            val all = (inlineComments ?: emptyList()) + (blockComments ?: emptyList())
            lastCallInnerComments = all.ifEmpty { null }
        } else {
            // Non-empty arg list: capture own-line comments between the last arg and `)`
            // (e.g. `f(arg\n  // comment 5\n)`). Append to the last arg's trailing comments
            // so the call emitter can emit them on their own line before `)`.
            val preCloseParenComments = leadingComments()
            if (preCloseParenComments != null) {
                val last = args.last()
                val merged = (last.trailingComments ?: emptyList()) + preCloseParenComments
                args[args.size - 1] = last.withTrailingComments(merged)
            }
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
        // B18.2: when the previous loop iteration ended in B17.7's comma-recovery
        // path, the next parameter is marked so checker diagnostics on it (and the
        // rest param preceding it) can be suppressed.
        var nextParamFromCommaRecovery = false
        while (token != SyntaxKind.CloseParen && token != SyntaxKind.EndOfFile) {
            var param = parseParameter()
            if (nextParamFromCommaRecovery) {
                param = param.copy(commaRecovered = true)
                nextParamFromCommaRecovery = false
            }
            // Prepend any comments collected from before the previous comma into this param's leadingComments.
            if (pendingLeadingComments != null) {
                val merged = pendingLeadingComments + (param.leadingComments ?: emptyList())
                param = param.copy(leadingComments = merged.ifEmpty { null })
                pendingLeadingComments = null
            }
            // Before consuming the comma, capture its leading comments (comments between param and comma).
            // These will become the leading comments of the NEXT parameter (comma-first style).
            val preCommaComments = leadingComments()
            if (!parseOptional(SyntaxKind.Comma)) {
                // No comma. Try recovery: if the next token looks like the start of another
                // parameter (identifier / `...` / binding-pattern open), TypeScript emits
                // TS1005 `,` expected and keeps parsing. Mirrors the recovery for
                // `constructor(...public rest: string[])` where `...public` parses as a rest
                // param named `public` and `rest` is the next param. Without this the second
                // parameter would be dropped and the constructor body would never be reached.
                val nextLooksLikeParam = token != SyntaxKind.CloseParen &&
                    token != SyntaxKind.EndOfFile &&
                    (isIdentifier() ||
                        token == SyntaxKind.DotDotDot ||
                        token == SyntaxKind.OpenBrace ||
                        token == SyntaxKind.OpenBracket)
                if (nextLooksLikeParam) {
                    // B18.2: squiggle covers the unexpected next token (e.g. `rest`
                    // in `(...public rest)`) — TypeScript points TS1005 at the
                    // identifier that should have been separated by a comma. For
                    // `...`/`{`/`[` (length-1 punctuation), the same span works.
                    val recPos = scanner.getTokenPos()
                    val recLen = (scanner.getPos() - recPos).coerceAtLeast(1)
                    reportError("',' expected.", code = 1005,
                        overrideStart = recPos, overrideLength = recLen)
                    params.add(param)
                    nextParamFromCommaRecovery = true
                    continue
                }
                // No comma and next token doesn't look like a param — we're done. Capture
                // leading comments of `)` as trailing of this last param.
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
            // Also capture inline comments after the comma (scanner treats them as trailing
            // because there's no preceding line break, but they are semantically leading
            // comments of the next parameter).
            val postCommaComments = trailingComments()
            if (!postCommaComments.isNullOrEmpty()) {
                pendingLeadingComments = (pendingLeadingComments ?: emptyList()) + postCommaComments
            }
        }
        parseExpected(SyntaxKind.CloseParen, eofRelated = false)
        // 17.200: TS1016 — required parameter cannot follow `?`-optional.
        // (`= initializer` does NOT trigger this — TypeScript's quirk: an
        // initializer with a following required param implicitly makes the
        // initializer required too, so no error.) Squiggle on the required
        // param's name; length = name text length for Identifier names.
        run {
            var sawOptional = false
            for (param in params) {
                if (param.questionToken) {
                    sawOptional = true
                    continue
                }
                if (sawOptional && !param.dotDotDotToken && param.initializer == null) {
                    val nm = param.name
                    if (nm is Identifier) {
                        reportError(
                            "A required parameter cannot follow an optional parameter.",
                            code = 1016, overrideStart = nm.pos, overrideLength = nm.text.length,
                        )
                    }
                }
            }
        }
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
        // Consume optional `!` (definite assignment assertion on parameter, e.g. `param!: Type`).
        // This is a TypeScript-specific syntax; the `!` is consumed but not stored since it has no
        // semantic effect after type erasure.
        parseOptional(SyntaxKind.Exclamation)
        // Capture trailing comments after the name before type annotation, since they will be lost
        // when the type annotation is parsed and erased (e.g. `...restGreetings /* comment */: string[]`)
        val nameTrailing = if (token == SyntaxKind.Colon || token == SyntaxKind.Question) {
            trailingComments()
        } else null
        val questionPos = if (token == SyntaxKind.Question) getPos() else -1
        val question = parseOptional(SyntaxKind.Question)
        val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
        val init = if (parseOptional(SyntaxKind.Equals)) parseAssignmentExpression() else null
        val trailing = trailingComments()
        val allTrailing = when {
            nameTrailing != null && trailing != null -> nameTrailing + trailing
            nameTrailing != null -> nameTrailing
            else -> trailing
        }
        // 17.169: TS1047 / TS1048 — rest parameter cannot be optional / cannot
        // have an initializer. TS1047 squiggle is on the `?` itself (length 1);
        // TS1048 squiggle is on the parameter name.
        if (dotDotDot && name is Identifier) {
            if (question && questionPos >= 0) {
                reportError(
                    "A rest parameter cannot be optional.",
                    code = 1047, overrideStart = questionPos, overrideLength = 1,
                )
            }
            if (init != null) {
                reportError(
                    "A rest parameter cannot have an initializer.",
                    code = 1048, overrideStart = name.pos, overrideLength = name.text.length,
                )
            }
        }
        return Parameter(
            name = name, type = type, initializer = init, dotDotDotToken = dotDotDot,
            questionToken = question, modifiers = modifiers, decorators = decorators, pos = pos, end = getEnd(),
            leadingComments = comments, trailingComments = allTrailing,
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
            // Allow empty <> (error recovery: constructor<>() etc.)
            while (token != SyntaxKind.GreaterThan && token != SyntaxKind.EndOfFile) {
                val p = parseTypeParameter() ?: return@tryScan null
                params.add(p)
                if (!parseOptional(SyntaxKind.Comma)) break
            }
            // B20.1: parser-recovery for missing `>` before parameter list — e.g.
            // `foo<U extends C<C<T>>(x: U)` (one `>` short). When we successfully
            // parsed at least one TypeParameter and now see `(` (the start of a
            // parameter list), emit TS1005 `'>' expected.` at the `(` position and
            // accept the parsed params. Without this, tryScan would roll back to
            // the original `<` and `parseParameterList` would emit a confusing
            // cascade (TS1005 `(` expected. at `<`, then TS1005 `)` expected. on the
            // recovered `<U extends C<C<T>>` partial-parse, etc.).
            if (token != SyntaxKind.GreaterThan && token == SyntaxKind.OpenParen && params.isNotEmpty()) {
                reportError("'>' expected.", code = 1005, overrideLength = 1)
                return@tryScan params
            }
            if (token != SyntaxKind.GreaterThan) return@tryScan null
            nextToken()
            params
        }
    }

    private fun parseTypeParameter(): TypeParameter? {
        val pos = getPos()
        val modifiers = mutableSetOf<ModifierFlag>()
        // Handle `const` modifier (TypeScript 5.0 const type parameters)
        if (token == SyntaxKind.ConstKeyword) {
            modifiers.add(ModifierFlag.Const)
            nextToken()
        }
        // Handle `in`/`out` variance modifiers (TypeScript 4.7+)
        while (token == SyntaxKind.InKeyword || token == SyntaxKind.OutKeyword) {
            modifiers.add(if (token == SyntaxKind.InKeyword) ModifierFlag.In else ModifierFlag.Out)
            nextToken()
        }
        if (!isIdentifier()) return null
        val name = parseIdentifier()
        var constraint = if (parseOptional(SyntaxKind.ExtendsKeyword)) parseType() else null
        // Flow-style constraint `<T: Base>` (typically JS sources): tsc reports
        // TS1005 "',' expected." at the `:` and keeps consuming — the TS8004
        // nodes-array span then covers `T: Base`. Absorb the type as the
        // constraint so the list parse succeeds and downstream `T` refs resolve.
        if (constraint == null && token == SyntaxKind.Colon) {
            reportError("',' expected.", code = 1005,
                overrideStart = scanner.getTokenPos(), overrideLength = 1)
            nextToken()
            constraint = parseType()
        }
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

    /**
     * Checks if the current token can follow type arguments in an expression context,
     * indicating an instantiation expression (e.g., `foo<number>` without a call).
     * Based on TypeScript's `canFollowTypeArgumentsInExpression`.
     */
    private fun canFollowTypeArgumentsInExpression(): Boolean = when (token) {
        // These tokens can follow a type argument list in an expression context
        SyntaxKind.Comma, SyntaxKind.Dot, SyntaxKind.QuestionDot,
        SyntaxKind.CloseParen, SyntaxKind.CloseBracket,
        SyntaxKind.Colon, SyntaxKind.Semicolon, SyntaxKind.Question,
        SyntaxKind.EqualsEquals, SyntaxKind.EqualsEqualsEquals,
        SyntaxKind.ExclamationEquals, SyntaxKind.ExclamationEqualsEquals,
        SyntaxKind.AmpersandAmpersand, SyntaxKind.BarBar,
        SyntaxKind.QuestionQuestion, SyntaxKind.Caret,
        SyntaxKind.Ampersand, SyntaxKind.Bar,
        SyntaxKind.CloseBrace, SyntaxKind.EndOfFile,
        SyntaxKind.Equals,
        // Binary keyword operators: Box<number> instanceof Object, key<T> in obj
        SyntaxKind.InstanceOfKeyword, SyntaxKind.InKeyword -> true
        else -> false
    }

    /**
     * Returns true if [expr] contains any optional chaining (`.?`, `?.()`, `?.[`).
     * Used to decide whether an instantiation expression boundary requires wrapping
     * in parentheses: `a?.b<T>.c` must emit `(a?.b).c` not `a?.b.c`.
     */
    private fun expressionHasOptionalChain(expr: Expression): Boolean = when (expr) {
        is PropertyAccessExpression -> expr.questionDotToken || expressionHasOptionalChain(expr.expression)
        is ElementAccessExpression -> expr.questionDotToken || expressionHasOptionalChain(expr.expression)
        is CallExpression -> expr.questionDotToken || expressionHasOptionalChain(expr.expression)
        else -> false
    }

    private fun tryParseTypeArguments(): List<TypeNode>? {
        inTypeArgsDepth++
        // Save diagnostic count: speculative `parseType()` below can leak errors via
        // `parseExpected` when probing non-type input as type args (e.g. `1 << 1`
        // entered the rescan path, then `<` was tried as a generic function type and
        // `parseParameterList` / `parseExpected(=>)` emitted false errors). `tryScan`
        // restores scanner state but not diagnostics — trim them on a null return so
        // the speculative attempt is fully invisible.
        val savedDiagCount = diagnostics.size
        try {
            val result = scanner.tryScan {
                // Rescan `<<` to `<` for nested generic type args like
                // `Foo<<T>(x: T) => R>` where the inner type starts with `<`.
                // tryScan restores scanner state if we ultimately bail.
                token = scanner.reScanLessThanToken()
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
                        // TS1110: Type expected — empty type argument position
                        reportError("Type expected.", code = 1110, overrideLength = 1)
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
            if (result == null) {
                while (diagnostics.size > savedDiagCount) diagnostics.removeAt(diagnostics.lastIndex)
            }
            return result
        } finally {
            inTypeArgsDepth--
        }
    }

    // 17.58b: in JS-like files, a class property without an explicit type
    // annotation may carry a leading JSDoc `@type { T }` comment that supplies
    // the type. Inner T is parsed via a sub-Parser; resulting positions point
    // into the JSDoc text rather than the original source, which is fine for
    // TS2564-style structural consumers.
    private fun parsePropertyTypeFromJSDoc(comments: List<Comment>?): TypeNode? {
        if (comments.isNullOrEmpty()) return null
        for (comment in comments) {
            if (comment.kind != SyntaxKind.MultiLineComment) continue
            val ct = comment.text
            if (!ct.startsWith("/**")) continue
            val typeText = extractAtTypeBraceContent(ct) ?: continue
            return parseTypeFromText(typeText, fileName)
        }
        return null
    }

    // 17.62: primitive-only variant of parsePropertyTypeFromJSDoc for use on
    // VariableDeclaration. The full sub-Parser approach (17.61 attempt) regressed
    // jsdocReferenceGlobalTypeInCommonJs_ts because TypeNode positions point into
    // the JSDoc-internal substring (offset 0), and downstream `getTypeFromTypeReference`
    // for unresolvable namespace paths (e.g. Puppeteer.Keyboard) emits TS2503 with
    // start position 0 — which maps to wrong source location. Restricting the
    // bridge to primitives produces a synthetic KeywordTypeNode that no
    // name-resolution path consumes positions for.
    private fun parsePrimitiveTypeFromJSDoc(comments: List<Comment>?): TypeNode? {
        if (comments.isNullOrEmpty()) return null
        for (comment in comments) {
            if (comment.kind != SyntaxKind.MultiLineComment) continue
            val ct = comment.text
            if (!ct.startsWith("/**")) continue
            val typeText = extractAtTypeBraceContent(ct)?.trim() ?: continue
            val kind = primitiveKeywordKindFor(typeText) ?: continue
            return KeywordTypeNode(kind = kind, pos = -1, end = -1)
        }
        return null
    }

    // 17.140: JSDoc `@param {T} name` extraction for parameters in JS-like files.
    // Mirrors 17.62's primitive-only `@type` bridge for var-decls — only primitive
    // keyword types (`string`/`number`/`boolean`/...) yield a synthetic
    // `KeywordTypeNode(pos=-1, end=-1)`. Non-primitive types (named refs, unions,
    // function types) bail to null. Returns a name → TypeNode map; callers apply
    // each entry to the matching Parameter when its `type` field is null.
    //
    // Conservative gate (primitive-only) avoids 17.61's revert risk: a sub-Parser
    // TypeNode pointing at JSDoc-internal positions would emit name-resolution
    // diagnostics with garbled squiggles. Primitives have no name — no risk.
    private fun parseJSDocParamPrimitiveTypeMap(comments: List<Comment>?): Map<String, TypeNode>? {
        if (!isJsLikeFile || comments.isNullOrEmpty()) return null
        var map: MutableMap<String, TypeNode>? = null
        for (comment in comments) {
            if (comment.kind != SyntaxKind.MultiLineComment) continue
            val ct = comment.text
            if (!ct.startsWith("/**")) continue
            var idx = 0
            while (idx < ct.length) {
                val tagIdx = ct.indexOf("@param", idx)
                if (tagIdx < 0) break
                val afterTag = if (tagIdx + 6 < ct.length) ct[tagIdx + 6] else ' '
                if (afterTag.isLetterOrDigit() || afterTag == '_') {
                    idx = tagIdx + 6
                    continue
                }
                var i = tagIdx + 6
                while (i < ct.length && (ct[i] == ' ' || ct[i] == '\t' || ct[i] == '\n' || ct[i] == '\r' || ct[i] == '*')) i++
                // Optional `{T}` brace-balanced type expression.
                var typeText: String? = null
                if (i < ct.length && ct[i] == '{') {
                    val typeStart = i + 1
                    var depth = 1; i++
                    while (i < ct.length && depth > 0) {
                        when (ct[i]) {
                            '{' -> depth++
                            '}' -> depth--
                        }
                        if (depth == 0) break
                        i++
                    }
                    if (i < ct.length && ct[i] == '}') {
                        typeText = ct.substring(typeStart, i).trim()
                        i++
                    }
                }
                while (i < ct.length && (ct[i] == ' ' || ct[i] == '\t')) i++
                // Optional `[name]` brackets.
                val hasBrackets = i < ct.length && ct[i] == '['
                if (hasBrackets) i++
                val nameStart = i
                while (i < ct.length && (ct[i].isLetterOrDigit() || ct[i] == '_' || ct[i] == '$')) i++
                val name = ct.substring(nameStart, i)
                idx = i
                if (name.isEmpty() || typeText == null) continue
                // Skip nested name (`@param obj.foo`) — not a top-level parameter binding.
                if (i < ct.length && ct[i] == '.') continue
                val kind = primitiveKeywordKindFor(typeText)
                if (kind != null) {
                    if (map == null) map = mutableMapOf()
                    if (name !in map!!) {
                        map[name] = KeywordTypeNode(kind = kind, pos = -1, end = -1)
                    }
                    continue
                }
                // B5.2: single-Identifier named ref (e.g. `@param {C} p`).
                // Compute absolute source positions so TS2314 / TS2304 fire
                // at the right column. Restricted to bare identifier — no
                // QualifiedName, no type args, no unions; wider patterns
                // would need position rewriting on nested TypeNodes.
                val tt = typeText
                if (tt.isNotEmpty() && (tt[0].isLetter() || tt[0] == '_' || tt[0] == '$') &&
                    tt.all { it.isLetterOrDigit() || it == '_' || it == '$' }) {
                    val braceOpen = ct.indexOf('{', tagIdx)
                    if (braceOpen in 0..<i) {
                        val braceEnd = ct.indexOf('}', braceOpen + 1)
                        if (braceEnd > braceOpen) {
                            val raw = ct.substring(braceOpen + 1, braceEnd)
                            val leadingWs = raw.length - raw.trimStart().length
                            val absStart = comment.pos + braceOpen + 1 + leadingWs
                            val absEnd = absStart + tt.length
                            val ident = Identifier(text = tt, pos = absStart, end = absEnd)
                            val typeRef = TypeReference(
                                typeName = ident,
                                typeArguments = null,
                                pos = absStart,
                                end = absEnd,
                            )
                            if (map == null) map = mutableMapOf()
                            if (name !in map!!) {
                                map[name] = typeRef
                            }
                        }
                    }
                }
            }
        }
        return map
    }

    /** Apply JSDoc `@param {primitive} name` types to params whose `type` is null,
     *  matched by Identifier name. Non-Identifier param names (destructuring) are
     *  preserved unchanged. Returns the original list when no JSDoc primitives match. */
    private fun applyJSDocParamPrimitiveTypes(
        params: List<Parameter>, comments: List<Comment>?,
    ): List<Parameter> {
        val map = parseJSDocParamPrimitiveTypeMap(comments) ?: return params
        var changed = false
        val out = params.map { p ->
            if (p.type != null) p
            else {
                val name = (p.name as? Identifier)?.text
                val t = name?.let { map[it] }
                if (t != null) {
                    changed = true
                    p.copy(type = t, typeFromJSDoc = true)
                } else p
            }
        }
        return if (changed) out else params
    }

    // 17.146 / B5.3: parse `/** @template T */` (or `@template T,U`) tags from
    // a declaration's leading comments and return synthetic TypeParameter nodes
    // with absolute source positions. JS-like files only. Conservative pattern:
    // a single tag line declares one or more comma-separated bare identifiers.
    // Constraints (`@template {Constraint} T`) and `@template T = Default` are
    // out of scope for this substep.
    private fun parseJSDocTemplateTypeParams(comments: List<Comment>?): List<TypeParameter>? {
        if (!isJsLikeFile || comments.isNullOrEmpty()) return null
        var out: MutableList<TypeParameter>? = null
        for (comment in comments) {
            if (comment.kind != SyntaxKind.MultiLineComment) continue
            val ct = comment.text
            if (!ct.startsWith("/**")) continue
            var idx = 0
            while (idx < ct.length) {
                val tagIdx = ct.indexOf("@template", idx)
                if (tagIdx < 0) break
                val afterTag = if (tagIdx + 9 < ct.length) ct[tagIdx + 9] else ' '
                if (afterTag.isLetterOrDigit() || afterTag == '_') {
                    idx = tagIdx + 9
                    continue
                }
                var i = tagIdx + 9
                // Skip whitespace, line-prefix `*`, and a possible `{Constraint}` block.
                while (i < ct.length && (ct[i] == ' ' || ct[i] == '\t')) i++
                if (i < ct.length && ct[i] == '{') {
                    var depth = 1; i++
                    while (i < ct.length && depth > 0) {
                        when (ct[i]) {
                            '{' -> depth++
                            '}' -> depth--
                        }
                        if (depth == 0) break
                        i++
                    }
                    if (i < ct.length && ct[i] == '}') i++
                    while (i < ct.length && (ct[i] == ' ' || ct[i] == '\t')) i++
                }
                // Read one or more comma-separated bare identifiers on this line.
                // Buffer (name, absStart, absEnd) so we can attach the tag span (computed
                // after the loop) to every identifier in this tag.
                val tagIds = mutableListOf<Triple<String, Int, Int>>()
                while (i < ct.length && ct[i] != '\n' && ct[i] != '\r') {
                    while (i < ct.length && (ct[i] == ' ' || ct[i] == '\t' || ct[i] == ',')) i++
                    if (i >= ct.length || ct[i] == '\n' || ct[i] == '\r') break
                    if (!(ct[i].isLetter() || ct[i] == '_' || ct[i] == '$')) break
                    val nameStart = i
                    while (i < ct.length && (ct[i].isLetterOrDigit() || ct[i] == '_' || ct[i] == '$')) i++
                    val tpName = ct.substring(nameStart, i)
                    if (tpName.isNotEmpty()) {
                        tagIds.add(Triple(tpName, comment.pos + nameStart, comment.pos + i))
                    }
                    // Stop after the name unless the next non-ws char is a comma.
                    while (i < ct.length && (ct[i] == ' ' || ct[i] == '\t')) i++
                    if (i >= ct.length || ct[i] != ',') break
                }
                // Compute tag span end: scan from `i` for either `*/` (comment close)
                // or a following JSDoc `@<tag>` at the start of a new line.
                var tagEndOffset = ct.length
                var j = i
                while (j < ct.length) {
                    val c = ct[j]
                    if (c == '*' && j + 1 < ct.length && ct[j + 1] == '/') {
                        tagEndOffset = j
                        break
                    }
                    if (c == '\n' || c == '\r') {
                        // Look for next non-whitespace, optionally past a leading `*`.
                        var k = j + 1
                        while (k < ct.length && (ct[k] == ' ' || ct[k] == '\t')) k++
                        if (k < ct.length && ct[k] == '*' && (k + 1 >= ct.length || ct[k + 1] != '/')) k++
                        while (k < ct.length && (ct[k] == ' ' || ct[k] == '\t')) k++
                        if (k < ct.length && ct[k] == '@') {
                            var kk = k + 1
                            while (kk < ct.length && ct[kk].isLetter()) kk++
                            if (kk > k + 1) {
                                tagEndOffset = k
                                break
                            }
                        }
                    }
                    j++
                }
                val tagAbsPos = comment.pos + tagIdx
                val tagAbsEnd = comment.pos + tagEndOffset
                for ((tpName, absStart, absEnd) in tagIds) {
                    val ident = Identifier(text = tpName, pos = absStart, end = absEnd)
                    if (out == null) out = mutableListOf()
                    if (out.none { it.name.text == tpName }) {
                        out.add(TypeParameter(
                            name = ident, fromJSDoc = true,
                            pos = absStart, end = absEnd,
                            jsDocTagPos = tagAbsPos, jsDocTagEnd = tagAbsEnd,
                        ))
                    }
                }
                idx = i
            }
        }
        return out
    }

    private fun primitiveKeywordKindFor(typeText: String): SyntaxKind? = when (typeText) {
        "string" -> SyntaxKind.StringKeyword
        "number" -> SyntaxKind.NumberKeyword
        "boolean" -> SyntaxKind.BooleanKeyword
        "any" -> SyntaxKind.AnyKeyword
        "unknown" -> SyntaxKind.UnknownKeyword
        "never" -> SyntaxKind.NeverKeyword
        "void" -> SyntaxKind.VoidKeyword
        "undefined" -> SyntaxKind.UndefinedKeyword
        "null" -> SyntaxKind.NullKeyword
        "bigint" -> SyntaxKind.BigIntKeyword
        "symbol" -> SyntaxKind.SymbolKeyword
        "object" -> SyntaxKind.ObjectKeyword
        else -> null
    }

    // Locates `@type { ... }` inside a JSDoc block comment and returns the
    // brace-balanced content (trimmed). Skips line-prefix `*` between the tag
    // and the opening brace so multi-line forms still resolve.
    private fun extractAtTypeBraceContent(commentText: String): String? {
        val typeIdx = commentText.indexOf("@type")
        if (typeIdx < 0) return null
        var i = typeIdx + 5
        // Skip whitespace, line breaks and JSDoc line-prefix `*` between the
        // tag and the opening brace.
        while (i < commentText.length) {
            val ch = commentText[i]
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '*') i++
            else break
        }
        if (i >= commentText.length || commentText[i] != '{') return null
        val start = i + 1
        var j = start
        var depth = 1
        while (j < commentText.length) {
            when (commentText[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return commentText.substring(start, j).trim().ifEmpty { null }
                    }
                }
            }
            j++
        }
        return null
    }

    /** Parses [text] as a standalone type expression. Returns null on parse failure. */
    private fun parseTypeFromText(text: String, fileName: String): TypeNode? {
        return try {
            val sub = Parser(text, fileName)
            sub.runParseTypeFromExternal()
        } catch (e: Throwable) {
            null
        }
    }

    private fun runParseTypeFromExternal(): TypeNode? {
        return try {
            nextToken()
            val parsed = parseType()
            if (token != SyntaxKind.EndOfFile) null else parsed
        } catch (e: Throwable) {
            null
        }
    }

    // ── Type parsing (parse to discard) ─────────────────────────────────────

    /**
     * TS1385/1386 (union) / TS1387/1388 (intersection): a FunctionType / ConstructorType
     * used as a DIRECT union/intersection member must be parenthesized. A *parenthesized*
     * function/ctor type parses as a [ParenthesizedType] (not a bare FunctionType/
     * ConstructorType), so the legal `string | (() => void)` shape never trips this.
     * [startPos] is the source position right after the preceding `|`/`&` (or the leading
     * one); the squiggle runs to the member's end (`scanner.getPrevTokenEnd()`, valid only
     * immediately after the member is parsed).
     */
    private fun reportUnparenthesizedFnTypeInUnionOrIntersection(member: TypeNode, startPos: Int, inUnion: Boolean) {
        val code: Int
        val kindWord: String
        when (member) {
            is FunctionType -> { code = if (inUnion) 1385 else 1387; kindWord = "Function" }
            is ConstructorType -> { code = if (inUnion) 1386 else 1388; kindWord = "Constructor" }
            else -> return
        }
        val ctxWord = if (inUnion) "a union" else "an intersection"
        val endPos = scanner.getPrevTokenEnd()
        reportError(
            "$kindWord type notation must be parenthesized when used in $ctxWord type.",
            code = code,
            overrideStart = startPos,
            overrideLength = (endPos - startPos).coerceAtLeast(1),
        )
    }

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
        val hadLeadingBar = parseOptional(SyntaxKind.Bar)
        val firstUnionMemberStart = if (hadLeadingBar) scanner.getPrevTokenEnd() else -1
        var type = parseIntersectionOrHigherType()
        if (hadLeadingBar) reportUnparenthesizedFnTypeInUnionOrIntersection(type, firstUnionMemberStart, inUnion = true)
        // Type predicate: X is T (valid as function return type annotations)
        // After parsing X as a type reference, if the next token is `is`, consume it
        // and parse the actual predicate type. Since we erase all types, the exact
        // node returned doesn't matter as long as we consume the right tokens.
        if (token == SyntaxKind.IsKeyword) {
            val paramName = type  // The type parsed so far is actually the parameter name
            nextToken()  // consume 'is'
            val predicateType = parseIntersectionOrHigherType()
            type = TypePredicate(
                parameterName = paramName,
                type = predicateType,
                pos = pos,
                end = getEnd()
            )
        }
        if (token == SyntaxKind.Bar) {
            val types = mutableListOf(type)
            while (parseOptional(SyntaxKind.Bar)) {
                val memberStart = scanner.getPrevTokenEnd()
                val member = parseIntersectionOrHigherType()
                reportUnparenthesizedFnTypeInUnionOrIntersection(member, memberStart, inUnion = true)
                types.add(member)
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
        val hadLeadingAmp = parseOptional(SyntaxKind.Ampersand)
        val firstAmpMemberStart = if (hadLeadingAmp) scanner.getPrevTokenEnd() else -1
        var type = parseNonUnionType()
        if (hadLeadingAmp) reportUnparenthesizedFnTypeInUnionOrIntersection(type, firstAmpMemberStart, inUnion = false)
        if (token == SyntaxKind.Ampersand) {
            val types = mutableListOf(type)
            while (parseOptional(SyntaxKind.Ampersand)) {
                val memberStart = scanner.getPrevTokenEnd()
                val member = parseNonUnionType()
                reportUnparenthesizedFnTypeInUnionOrIntersection(member, memberStart, inUnion = false)
                types.add(member)
            }
            type = IntersectionType(types = types, pos = pos, end = getEnd())
        }
        return type
    }

    private fun parseNonUnionType(): TypeNode {
        val pos = getPos()
        // Error recovery: leading ! in type position (e.g. a: !string) — skip it.
        // In .ts files emit TS17020 after the type is parsed (JSDoc non-nullable
        // is not valid TS syntax); JS files keep the silent JSDoc recovery.
        var leadingExclPos: Int = -1
        if (token == SyntaxKind.Exclamation) {
            leadingExclPos = getPos()
            nextToken()
        }
        // Error recovery: leading ? in type position (JSDoc nullable, e.g. ?string) — skip it.
        // Bare ? with no following type (e.g. <?>) is treated as JSDoc unknown → any.
        // In type-args context emit TS17020 (?TYPE) or TS8020 (bare ?) — JSDoc nullable
        // is not valid TS syntax. Other contexts (regular annotations, tuple elements)
        // remain silently recovered to avoid regressing valid syntax shapes.
        var leadingQuestionPos: Int = -1
        if (token == SyntaxKind.Question) {
            leadingQuestionPos = getPos()
            nextToken()
            if (!isStartOfType(token)) {
                if (inTypeArgsDepth > 0) {
                    reportError(
                        message = "JSDoc types can only be used inside documentation comments.",
                        code = 8020,
                        overrideStart = leadingQuestionPos,
                        overrideLength = 1,
                    )
                }
                return KeywordTypeNode(kind = SyntaxKind.AnyKeyword, pos = pos, end = getEnd())
            }
        }
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
            // `typeof import(...)` — the import(...) is an ImportType, not a qualified name
            if (token == SyntaxKind.ImportKeyword) {
                val importType = parseImportType()
                return TypeQuery(exprName = importType, typeArguments = null, pos = pos, end = getEnd())
            }
            val name = parseQualifiedName()
            val typeArgs = parseTypeArgumentsOpt()
            var type: TypeNode = TypeQuery(exprName = name, typeArguments = typeArgs, pos = pos, end = getEnd())
            // Handle array suffix `typeof X[]` and indexed-access `typeof X[K]`.
            // ASI: do not consume [ on a new line.
            while (token == SyntaxKind.OpenBracket && !scanner.hasPrecedingLineBreak()) {
                if (scanner.lookAhead { scanner.scan(); scanner.getToken() == SyntaxKind.CloseBracket }) {
                    nextToken(); nextToken()
                    type = ArrayType(elementType = type, pos = pos, end = getEnd())
                } else {
                    nextToken()
                    val indexType = parseType()
                    parseExpected(SyntaxKind.CloseBracket)
                    type = IndexedAccessType(objectType = type, indexType = indexType, pos = pos, end = getEnd())
                }
            }
            return type
        }

        var type = parsePrimaryType()

        // Array type suffix: T[], T[][]
        // TypeScript applies ASI here: do not consume [ that is on a new line
        // (e.g. method return type `boolean` followed by `[key: type]` index signature)
        while (token == SyntaxKind.OpenBracket && !scanner.hasPrecedingLineBreak()) {
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

        // Capture end of type proper (before any trailing modifier consumption) for
        // diagnostic span construction.
        val typeProperEnd = scanner.getPrevTokenEnd()

        // Error recovery: trailing ! in type position (e.g. string!) — skip it.
        // In .ts files emit TS17019 (JSDoc non-nullable is not valid TS syntax).
        if (token == SyntaxKind.Exclamation) {
            val exclEnd = scanner.getPos()
            nextToken()
            if (!isJsLikeFile) {
                val typeText = source.substring(type.pos, typeProperEnd)
                reportError(
                    message = "'!' at the end of a type is not valid TypeScript syntax. Did you mean to write '$typeText'?",
                    code = 17019,
                    overrideStart = type.pos,
                    overrideLength = exclEnd - type.pos,
                )
            }
        }

        // Error recovery: trailing ? in type position (JSDoc nullable, e.g. string?) — skip it.
        // Must NOT consume ? when followed by a type-start token, as that indicates a
        // conditional type (T extends U ? X : Y) where ? belongs to the outer context.
        // In type-args context (and outside tuple, where `?` is a legitimate optional
        // marker), emit TS17019 — JSDoc trailing-? is not valid TS syntax.
        if (token == SyntaxKind.Question
            && !scanner.lookAhead { scanner.scan(); isStartOfType(scanner.getToken()) }
        ) {
            val questionEnd = scanner.getPos()
            nextToken()
            if (inTupleTypeDepth == 0) {
                val typeText = source.substring(type.pos, typeProperEnd)
                val suggestion = when (typeText) {
                    "any", "unknown", "never", "void", "undefined" -> typeText
                    else -> "$typeText | undefined"
                }
                reportError(
                    message = "'?' at the end of a type is not valid TypeScript syntax. Did you mean to write '$suggestion'?",
                    code = 17019,
                    overrideStart = type.pos,
                    overrideLength = questionEnd - type.pos,
                )
            }
        }

        if (leadingQuestionPos >= 0) {
            val combinedEnd = scanner.getPrevTokenEnd()
            val typeText = source.substring(type.pos, typeProperEnd)
            val suggestion = when (typeText) {
                "any", "unknown", "never", "void", "undefined" -> typeText
                else -> "$typeText | null | undefined"
            }
            reportError(
                message = "'?' at the start of a type is not valid TypeScript syntax. Did you mean to write '$suggestion'?",
                code = 17020,
                overrideStart = leadingQuestionPos,
                overrideLength = combinedEnd - leadingQuestionPos,
            )
        }

        if (leadingExclPos >= 0 && !isJsLikeFile) {
            val combinedEnd = scanner.getPrevTokenEnd()
            val typeText = source.substring(type.pos, typeProperEnd)
            reportError(
                message = "'!' at the start of a type is not valid TypeScript syntax. Did you mean to write '$typeText'?",
                code = 17020,
                overrideStart = leadingExclPos,
                overrideLength = combinedEnd - leadingExclPos,
            )
            // Downstream anchors (e.g. TS2355 at the return-type node) span from the `!`
            // in tsc — extend the node's pos to cover it for the common shapes.
            type = when (val t = type) {
                is KeywordTypeNode -> t.copy(pos = leadingExclPos)
                is TypeReference -> t.copy(pos = leadingExclPos)
                else -> t
            }
        }

        return type
    }

    /** Returns true if the given token kind can start a type. Safe to use inside scanner.lookAhead. */
    private fun isStartOfType(tok: SyntaxKind = token): Boolean = when (tok) {
        AnyKeyword, StringKeyword, NumberKeyword, BooleanKeyword,
        BigIntKeyword, SymbolKeyword, VoidKeyword, NeverKeyword,
        ObjectKeyword, UnknownKeyword, UndefinedKeyword, NullKeyword,
        ThisKeyword, NewKeyword, ImportKeyword,
        OpenParen, OpenBracket, OpenBrace, LessThan,
        StringLiteral, NumericLiteral, BigIntLiteral, TrueKeyword, FalseKeyword,
        Minus, DotDotDot,
        Backtick, NoSubstitutionTemplateLiteral, TemplateHead,
        TypeOfKeyword, KeyOfKeyword, UniqueKeyword, ReadonlyKeyword, InferKeyword -> true
        else -> isIdentifierToken(tok)
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
            StringLiteral, NumericLiteral, BigIntLiteral, TrueKeyword,
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
                // B65.1: build a TemplateLiteralType AST node so the checker can
                // preserve the source text for diagnostics. We capture the raw
                // source slice into `head.rawText` (a placeholder — spans stay
                // empty) instead of building proper head + spans because the
                // checker's display path just needs the rendered text, not the
                // structural pieces.
                skipTemplateType()
                val srcEnd = scanner.getPrevTokenEnd()
                val raw = if (pos in 0..srcEnd && srcEnd <= source.length) source.substring(pos, srcEnd) else ""
                val head = StringLiteralNode(text = "", rawText = raw, pos = pos, end = srcEnd)
                TemplateLiteralType(head = head, templateSpans = emptyList(), pos = pos, end = getEnd())
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
        // Save diagnostics count — parseParameter() inside tryScan may report false TS1003
        // when (()=>c)[] is a parenthesized function type, not a parameter list.
        val savedDiagCount = diagnostics.size
        var emptyFnTypeMissingArrowPos: Int? = null  // captured when () lacks => (TS1005 case)
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
            if (token != SyntaxKind.EqualsGreaterThan) {
                // `()` followed by something other than `=>` cannot be a parenthesized type
                // (parens require an inner type). Treat as a function-type missing its arrow
                // and capture the next-token position for a TS1005 diagnostic below.
                // Position = where `=>` should appear (start of the token after the empty `)`).
                if (params.isEmpty()) {
                    emptyFnTypeMissingArrowPos = scanner.getTokenPos()
                    return@tryScan FunctionType(
                        parameters = params,
                        type = KeywordTypeNode(kind = SyntaxKind.AnyKeyword, pos = pos, end = getEnd()),
                        pos = pos,
                        end = getEnd(),
                    )
                }
                // Recovery: if at least one parameter has a TYPE ANNOTATION, the `(...)`
                // unambiguously names a function-type parameter list (not a parenthesized type,
                // which can't contain `name: Type`). Accept as a function type with synthetic
                // `any` return and emit TS1005 `=>` expected, so the inner `(n: number)` of a
                // malformed annotation like `type F2 = (n: number): string;` is consumed as a
                // single unit instead of leaking `number;` as a top-level expression statement.
                if (params.any { it.type != null }) {
                    emptyFnTypeMissingArrowPos = scanner.getTokenPos()
                    return@tryScan FunctionType(
                        parameters = params,
                        type = KeywordTypeNode(kind = SyntaxKind.AnyKeyword, pos = pos, end = getEnd()),
                        pos = pos,
                        end = getEnd(),
                    )
                }
                return@tryScan null
            }
            nextToken() // consume =>
            val returnType = parseType()
            FunctionType(parameters = params, type = returnType, pos = pos, end = getEnd())
        }
        emptyFnTypeMissingArrowPos?.let { closePos ->
            reportError("'=>' expected.", code = 1005, overrideLength = 1, overrideStart = closePos)
        }
        if (funcType != null) return funcType
        // Discard any diagnostics reported speculatively during the failed tryScan
        while (diagnostics.size > savedDiagCount) diagnostics.removeAt(diagnostics.lastIndex)
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
        inTupleTypeDepth++
        try {
            while (token != SyntaxKind.CloseBracket && token != SyntaxKind.EndOfFile) {
                // Labeled tuple elements: `name: Type` or `name?: Type` or `...name: Type`
                val isRest = parseOptional(SyntaxKind.DotDotDot)
                val isLabeledElement = isIdentifier() && lookAhead {
                    nextToken()
                    when {
                        token == SyntaxKind.Colon -> true
                        token == SyntaxKind.Question -> { nextToken(); token == SyntaxKind.Colon }
                        else -> false
                    }
                }
                if (isLabeledElement) {
                    // Skip label (identifier) and optional `?`
                    nextToken() // consume identifier (label)
                    parseOptional(SyntaxKind.Question) // optional `?`
                    parseExpected(SyntaxKind.Colon) // consume `:`
                }
                val elementType = parseType()
                parseOptional(SyntaxKind.Question) // optional tuple element: string?, number?
                elements.add(if (isRest) RestType(type = elementType, pos = pos, end = getEnd()) else elementType)
                if (!parseOptional(SyntaxKind.Comma)) break
            }
        } finally {
            inTupleTypeDepth--
        }
        parseExpected(SyntaxKind.CloseBracket)
        return TupleType(elements = elements, pos = pos, end = getEnd())
    }

    private fun parseTypeLiteralOrMappedType(): TypeNode {
        val pos = getPos()
        parseExpected(SyntaxKind.OpenBrace)
        // B57.3a: Detect single-mapped-type shape `{ readonly? [K in C as N]?: V }`
        // and emit a real MappedType AST node instead of the placeholder
        // PropertyDeclaration-inside-TypeLiteral. Detection requires the OPTIONAL
        // leading `readonly` / `+readonly` / `-readonly` modifier, then `[`,
        // Identifier, `in`. After the closing `}`, no further members may follow
        // (single-entry brace contents). On any non-match, fall through to the
        // original TypeLiteral path so multi-member braces and non-mapped braces
        // are unchanged.
        if (isSingleMappedTypeBraceContents()) {
            return parseMappedType(pos)
        }
        val savedInTypeLiteral = inTypeLiteralForErrorWording
        inTypeLiteralForErrorWording = true
        val members = try { parseInterfaceMembers() } finally { inTypeLiteralForErrorWording = savedInTypeLiteral }
        if (interfaceMembersBailedOnKeyword) {
            interfaceMembersBailedOnKeyword = false
        } else {
            parseExpected(SyntaxKind.CloseBrace)
        }
        return TypeLiteral(members = members, pos = pos, end = getEnd())
    }

    /**
     * Lookahead detector for the single-mapped-type brace shape, called RIGHT
     * AFTER `{` has been consumed. Recognizes:
     *   - optional `readonly` / `+readonly` / `-readonly`
     *   - `[` `Identifier` `in`
     * Restores scanner state. Returns true only when the next non-`{` token
     * begins a mapped-type entry. Multi-member braces and non-mapped braces
     * still go through the TypeLiteral path.
     */
    private fun isSingleMappedTypeBraceContents(): Boolean {
        return scanner.lookAhead {
            var t = scanner.getToken()
            // Optional leading +/- before readonly
            if (t == SyntaxKind.Plus || t == SyntaxKind.Minus) {
                scanner.scan()
                t = scanner.getToken()
            }
            // Optional `readonly` (keyword OR contextual identifier)
            if (t == SyntaxKind.ReadonlyKeyword ||
                (isIdentifierToken(t) && scanner.getTokenValue() == "readonly")
            ) {
                scanner.scan()
                t = scanner.getToken()
            }
            // Must now see `[` `Identifier` `in`
            if (t != SyntaxKind.OpenBracket) return@lookAhead false
            scanner.scan()
            if (!isIdentifierToken(scanner.getToken())) return@lookAhead false
            scanner.scan()
            scanner.getToken() == SyntaxKind.InKeyword
        }
    }

    /**
     * B57.3a: Parse `{ readonly? [K in C as N]?: V }` into a MappedType AST.
     * `pos` is the position of the opening `{` (already consumed by caller).
     * Emits TS7039 when the value type is omitted under noImplicitAny.
     */
    private fun parseMappedType(pos: Int): TypeNode {
        // Optional readonly modifier (with optional +/- prefix)
        var readonlyToken = false
        if (token == SyntaxKind.Plus || token == SyntaxKind.Minus) {
            // +readonly / -readonly: consume modifier sign, then readonly
            nextToken()
            if (token == SyntaxKind.ReadonlyKeyword ||
                (isIdentifier() && scanner.getTokenValue() == "readonly")
            ) {
                readonlyToken = true
                nextToken()
            }
        } else if (token == SyntaxKind.ReadonlyKeyword ||
            (isIdentifier() && scanner.getTokenValue() == "readonly")
        ) {
            readonlyToken = true
            nextToken()
        }
        parseExpected(SyntaxKind.OpenBracket)
        val tpPos = getPos()
        val tpName = parseIdentifier()
        parseExpected(SyntaxKind.InKeyword)
        val constraint = parseType()
        val tp = TypeParameter(name = tpName, constraint = constraint, pos = tpPos, end = getEnd())
        // Optional `as` clause for key-remapping
        val nameType = if (parseOptional(SyntaxKind.AsKeyword)) parseType() else null
        parseExpected(SyntaxKind.CloseBracket)
        // Optional `?`/`+?`/`-?` after `]`
        var questionToken = false
        when (token) {
            SyntaxKind.Question -> { questionToken = true; nextToken() }
            SyntaxKind.Plus, SyntaxKind.Minus -> {
                nextToken()
                if (token == SyntaxKind.Question) { questionToken = true; nextToken() }
            }
            else -> {}
        }
        val mappedTypeEnd = scanner.getPrevTokenEnd()
        val hasValueType = parseOptional(SyntaxKind.Colon)
        val valueType = if (hasValueType) parseType() else null
        // TS7039: mapped type without an explicit value type implicitly types
        // members as `any`. Same emission shape as the placeholder branch in
        // parseIndexSignatureOrProperty.
        if (!hasValueType && noImplicitAny) {
            var braceStart = pos - 1
            while (braceStart > 0 && source[braceStart] != '{') braceStart--
            val sqStart = if (braceStart >= 0 && source[braceStart] == '{') braceStart else pos
            var braceEnd = mappedTypeEnd
            while (braceEnd < source.length && source[braceEnd] != '}') braceEnd++
            val sqEnd = if (braceEnd < source.length && source[braceEnd] == '}') braceEnd + 1 else mappedTypeEnd
            reportError(
                "Mapped object type implicitly has an 'any' template type.",
                code = 7039, overrideStart = sqStart, overrideLength = (sqEnd - sqStart).coerceAtLeast(1),
            )
        }
        // Consume optional trailing `;` / `,` (mapped-type entries inside braces
        // tolerate a single terminator before `}`).
        parseOptional(SyntaxKind.Semicolon) || parseOptional(SyntaxKind.Comma)
        parseExpected(SyntaxKind.CloseBrace)
        return MappedType(
            typeParameter = tp,
            nameType = nameType,
            type = valueType,
            questionToken = questionToken,
            readonlyToken = readonlyToken,
            pos = pos,
            end = getEnd(),
        )
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
            // Use corrected raw text when the identifier starts with an invalid unicode escape
            // (e.g. `\u0031a` where `1` is not a valid identifier start → corrected to `u0031a`).
            val raw = scanner.getCorrectedRawText() ?: scanner.getTokenText()
            // Only store rawText if it differs (contains \uXXXX escapes)
            val rawText = if (raw != value) raw else null
            // Report invalid unicode escapes (e.g. \u003 with only 3 hex digits)
            if (scanner.hasInvalidUnicodeEscapeInToken()) {
                val escapePos = scanner.getInvalidUnicodeEscapePos()
                reportError("Invalid character.", code = 1127, overrideLength = 0,
                    overrideStart = if (escapePos >= 0) escapePos else null)
            }
            nextToken()
            return Identifier(text = value, rawText = rawText, pos = pos, end = getEnd())
        } else {
            reportError("Identifier expected.", code = 1003)
            return Identifier(text = "", pos = pos, end = getEnd())
        }
    }

    private fun parseIdentifierName(): Identifier {
        val pos = getPos()
        // Capture both scanner trailing comments (inline comments from previous token with no
        // newline, e.g. point./*2*/x) and leading comments (comments after a newline).
        val trailing = trailingComments()
        val leading = leadingComments()
        val comments = when {
            trailing != null && leading != null -> trailing + leading
            trailing != null -> trailing
            else -> leading
        }
        val value = scanner.getTokenValue()
        // Use corrected raw text when the identifier starts with an invalid unicode escape
        val raw = scanner.getCorrectedRawText() ?: scanner.getTokenText()
        val rawText = if (raw != value) raw else null
        // Report invalid unicode escapes (e.g. \u003 with only 3 hex digits)
        if (scanner.hasInvalidUnicodeEscapeInToken()) {
            val escapePos = scanner.getInvalidUnicodeEscapePos()
            reportError("Invalid character.", code = 1127, overrideLength = 0,
                overrideStart = if (escapePos >= 0) escapePos else null)
        }
        nextToken()
        return Identifier(text = value, rawText = rawText, leadingComments = comments, pos = pos, end = getEnd())
    }

    /**
     * Emits TS1125/TS1198/TS1487/TS1488 diagnostics for escape sequence errors
     * found in the last scanned string/template literal token. Call BEFORE
     * [nextToken] to capture errors.
     *
     * B70.8: When [suppressOctalIllegal] is true (set by tagged-template parser
     * paths), ALL escape-error diagnostics for THIS token are suppressed —
     * TypeScript permits any escape sequence (octal, illegal, malformed hex /
     * unicode) inside tagged templates because the tag function receives the
     * RAW text and may interpret the escape itself.
     */
    private fun emitStringEscapeErrors(suppressOctalIllegal: Boolean = false) {
        val escapeErrors = scanner.getAndClearStringEscapeErrors()
        if (suppressOctalIllegal) return
        for (err in escapeErrors) {
            val (line, character) = getLineAndCharacterOfPosition(err.start)
            diagnostics.add(Diagnostic(
                message = err.message,
                category = DiagnosticCategory.Error,
                code = err.code,
                fileName = fileName,
                line = line,
                character = character,
                start = err.start,
                length = err.length,
            ))
        }
    }

    private fun parseStringLiteral(): StringLiteralNode {
        val pos = getPos()
        val raw = scanner.getTokenText()
        val value = scanner.getTokenValue()
        val startsWithQuote = raw.isNotEmpty() && (raw[0] == '\'' || raw[0] == '"')
        val singleQuote = raw.startsWith("'")
        val quote = if (singleQuote) '\'' else '"'
        // Detect unterminated string: raw token doesn't end with the opening quote char
        // Only valid when the raw token actually starts with a quote — error-recovery may
        // call this fn when the current token isn't a real StringLiteral (e.g. Identifier).
        val isUnterminated = startsWithQuote && raw.last() != quote
        val endedAfterBackslash = scanner.didStringEndAfterBackslash()
        // Store raw content between quotes to preserve escape sequences (e.g. \u2730, \n)
        val rawContent = when {
            !isUnterminated && raw.length >= 2 -> raw.substring(1, raw.length - 1)
            isUnterminated -> if (raw.length >= 1) raw.substring(1) else raw  // content after opening quote
            else -> raw
        }
        // Emit TS1487/TS1488 diagnostics for any escape errors found in this string
        emitStringEscapeErrors()
        nextToken()
        // For unterminated strings: TS1126 "Unexpected end of text." if the source ended
        // mid-escape (right after a `\`); otherwise TS1002 "Unterminated string literal."
        // Position is the end of the string literal text (after nextToken, getPrevTokenEnd
        // returns position right after the just-consumed string).
        if (isUnterminated) {
            val emitPos = scanner.getPrevTokenEnd()
            if (endedAfterBackslash) {
                reportError("Unexpected end of text.", code = 1126,
                    overrideStart = emitPos, overrideLength = 0)
            } else {
                reportError("Unterminated string literal.", code = 1002,
                    overrideStart = emitPos, overrideLength = 0)
            }
        }
        return StringLiteralNode(text = value, singleQuote = singleQuote, rawText = rawContent,
            isUnterminated = isUnterminated, pos = pos, end = getEnd())
    }

    private fun parseNumericLiteral(): NumericLiteralNode {
        val pos = getPos()
        val text = scanner.getTokenText()
        val isLegacyOctal = scanner.isLegacyOctalLiteralToken()
        val isLeadingZeroDecimal = scanner.isLeadingZeroDecimalLiteralToken()
        val trailingSeps = scanner.getNumericTrailingSeparatorPositions()
        val doubleSeps = scanner.getNumericDoubleSeparatorPositions()
        val missingExpDigitsPos = scanner.getNumericMissingExponentDigitsPos()
        val identFollow = scanner.getNumericIdentifierFollow()
        val hasPrecedingMinus = prevToken == SyntaxKind.Minus
        // Capture the end of the previous token (right after '-' if present) BEFORE advancing
        val prevEnd = scanner.getPrevTokenEnd()
        nextToken()
        if (missingExpDigitsPos >= 0) {
            reportError("Digit expected.", code = 1124,
                overrideStart = missingExpDigitsPos, overrideLength = 0)
        }
        // TS1351 from the scanner's identifier-follow detection. tsc's
        // parseErrorAtPosition suppresses a diagnostic at the SAME start as the last
        // one — for `1ee` the TS1124 above and this TS1351 share a start, so the
        // TS1351 is dropped (matching tsc's baseline).
        if (identFollow != null && identFollow.first != missingExpDigitsPos) {
            reportError("An identifier or keyword cannot immediately follow a numeric literal.",
                code = 1351, overrideStart = identFollow.first, overrideLength = identFollow.second)
        }
        for (sp in trailingSeps) {
            reportError("Numeric separators are not allowed here.", code = 6188,
                overrideStart = sp, overrideLength = 1)
        }
        for (sp in doubleSeps) {
            reportError("Multiple consecutive numeric separators are not permitted.", code = 6189,
                overrideStart = sp, overrideLength = 1)
        }
        if (isLegacyOctal) {
            // TS1121: Octal literals are not allowed. Use the syntax '0o{digits}'.
            // Strip the leading '0' and any subsequent leading zeros to get minimal octal representation.
            val rawDigits = text.substring(1) // strip leading '0'
            val octalDigits = rawDigits.trimStart('0').ifEmpty { "0" }
            val prefix = if (hasPrecedingMinus) "-" else ""
            // When preceded by '-', the squiggle spans '-' + literal (e.g., -01 = 3 chars)
            val errorStart = if (hasPrecedingMinus) prevEnd - 1 else pos
            val errorLength = if (hasPrecedingMinus) 1 + text.length else text.length
            reportError(
                "Octal literals are not allowed. Use the syntax '${prefix}0o$octalDigits'.",
                code = 1121,
                overrideStart = errorStart,
                overrideLength = errorLength
            )
        } else if (isLeadingZeroDecimal) {
            // TS1489: Decimals with leading zeros are not allowed.
            reportError(
                "Decimals with leading zeros are not allowed.",
                code = 1489,
                overrideStart = pos,
                overrideLength = text.length
            )
        }
        // (B61.2's trailing-dot TS1351 — `1.toString` — is subsumed by the scanner's
        // identifier-follow detection flushed above.)
        // Capture trailing comments only when the next token is a dot (property access).
        // This preserves `0 /* comment */.toString()` but avoids stealing comments that
        // belong to the enclosing statement (e.g. `await 3 /*comment*/` → comment trails stmt).
        val trailingComments = if (token == SyntaxKind.Dot) scanner.consumeTrailingComments() else null
        return NumericLiteralNode(text = text, pos = pos, end = getEnd(), trailingComments = trailingComments)
    }

    /** Checks if the current token can start an expression. */
    private fun isStartOfExpression(): Boolean = when (token) {
        SyntaxKind.NumericLiteral, SyntaxKind.BigIntLiteral,
        SyntaxKind.StringLiteral, SyntaxKind.RegularExpressionLiteral,
        SyntaxKind.NoSubstitutionTemplateLiteral, SyntaxKind.TemplateHead,
        SyntaxKind.OpenParen, SyntaxKind.OpenBracket, SyntaxKind.OpenBrace,
        SyntaxKind.Plus, SyntaxKind.Minus, SyntaxKind.Tilde,
        SyntaxKind.Exclamation, SyntaxKind.PlusPlus, SyntaxKind.MinusMinus,
        SyntaxKind.TypeOfKeyword, SyntaxKind.VoidKeyword, SyntaxKind.DeleteKeyword,
        SyntaxKind.ThisKeyword, SyntaxKind.SuperKeyword, SyntaxKind.NewKeyword,
        SyntaxKind.TrueKeyword, SyntaxKind.FalseKeyword, SyntaxKind.NullKeyword,
        SyntaxKind.ClassKeyword, SyntaxKind.FunctionKeyword,
        SyntaxKind.YieldKeyword, SyntaxKind.AwaitKeyword,
        SyntaxKind.ImportKeyword, SyntaxKind.Slash, SyntaxKind.SlashEquals,
        SyntaxKind.LessThan, SyntaxKind.DotDotDot -> true
        else -> isIdentifier()
    }

    private fun isIdentifier(): Boolean = isIdentifierToken(token)

    private fun isIdentifierToken(t: SyntaxKind): Boolean =
        t == SyntaxKind.Identifier ||
                t == SyntaxKind.TypeKeyword ||
                t == SyntaxKind.AbstractKeyword ||
                t == SyntaxKind.AsKeyword ||
                t == SyntaxKind.AssertsKeyword ||
                t == SyntaxKind.AsyncKeyword ||
                t == SyntaxKind.AwaitKeyword ||
                t == SyntaxKind.ConstructorKeyword ||
                t == SyntaxKind.DeclareKeyword ||
                t == SyntaxKind.GetKeyword ||
                t == SyntaxKind.GlobalKeyword ||
                t == SyntaxKind.FromKeyword ||
                t == SyntaxKind.ImplementsKeyword ||
                t == SyntaxKind.InterfaceKeyword ||
                t == SyntaxKind.IsKeyword ||
                t == SyntaxKind.KeyOfKeyword ||
                t == SyntaxKind.LetKeyword ||
                t == SyntaxKind.ModuleKeyword ||
                t == SyntaxKind.NamespaceKeyword ||
                t == SyntaxKind.NeverKeyword ||
                t == SyntaxKind.OfKeyword ||
                t == SyntaxKind.OutKeyword ||
                t == SyntaxKind.OverrideKeyword ||
                t == SyntaxKind.ReadonlyKeyword ||
                t == SyntaxKind.RequireKeyword ||
                t == SyntaxKind.SatisfiesKeyword ||
                t == SyntaxKind.SetKeyword ||
                t == SyntaxKind.StaticKeyword ||
                t == SyntaxKind.UniqueKeyword ||
                t == SyntaxKind.UnknownKeyword ||
                t == SyntaxKind.UsingKeyword ||
                t == SyntaxKind.AnyKeyword ||
                t == SyntaxKind.BooleanKeyword ||
                t == SyntaxKind.BigIntKeyword ||
                t == SyntaxKind.NumberKeyword ||
                t == SyntaxKind.ObjectKeyword ||
                t == SyntaxKind.StringKeyword ||
                t == SyntaxKind.SymbolKeyword ||
                t == SyntaxKind.UndefinedKeyword ||
                t == SyntaxKind.AccessorKeyword ||
                // Strict-mode future reserved words: valid identifiers in non-strict/non-generator contexts.
                // TypeScript allows these as identifier names (var private = 0, var yield = 0, etc.)
                t == SyntaxKind.PackageKeyword ||
                t == SyntaxKind.PrivateKeyword ||
                t == SyntaxKind.ProtectedKeyword ||
                t == SyntaxKind.PublicKeyword ||
                t == SyntaxKind.YieldKeyword

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
        DotDotDot -> "..."
        else -> kind.name
    }

}

private val RESERVED_TYPE_KEYWORD_NAMES = setOf(
    "string", "number", "boolean", "any", "unknown", "never",
    "object", "symbol", "bigint", "undefined",
)

/**
 * B68.4: Keyword TypeNode kinds that are valid as the parameter type of an index
 * signature. Per TypeScript, only `string`, `number`, `symbol`, and (since 4.4)
 * template literal types are allowed. Anything else (any, boolean, void, etc.)
 * triggers TS1268 from the Checker. The Parser uses this set to suppress the
 * conflicting TS1021 "must have a type annotation" emission for the same sig.
 */
private val INDEX_SIG_ALLOWED_PARAM_KEYWORDS = setOf(
    SyntaxKind.StringKeyword,
    SyntaxKind.NumberKeyword,
    SyntaxKind.SymbolKeyword,
)

/**
 * Compute an array of line start positions (0-based byte offsets where each line begins).
 * The first entry is always 0 (start of the first line).
 */
private fun computeLineStarts(text: String): IntArray {
    val starts = mutableListOf(0)
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (ch == '\r') {
            if (i + 1 < text.length && text[i + 1] == '\n') {
                i++ // skip \r in \r\n
            }
            starts.add(i + 1)
        } else if (ch == '\n') {
            starts.add(i + 1)
        }
        i++
    }
    return starts.toIntArray()
}
