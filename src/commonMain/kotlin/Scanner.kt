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
 * A pull-based lexer/scanner for TypeScript source text.
 *
 * Tokenizes a TypeScript source string into a stream of [SyntaxKind] tokens.
 * Each call to [scan] advances to the next token and returns its kind.
 *
 * ### Example
 * ```kotlin
 * val scanner = Scanner("const x: number = 42;")
 * while (true) {
 *     val kind = scanner.scan()
 *     if (kind == SyntaxKind.EndOfFile) break
 *     println("${scanner.getTokenText()} -> $kind")
 * }
 * ```
 */
class Scanner(private val text: String) {

    private val end: Int = text.length

    /** Current scan position (next character to be read). */
    private var pos: Int = 0

    /** The kind of the current token. */
    private var token: SyntaxKind = SyntaxKind.Unknown

    /** Start position of the current token in the source. */
    private var tokenPos: Int = 0

    /** The resolved value of the current token (for string/numeric literals). */
    private var tokenValue: String = ""

    /** Leading comments collected before the current token. */
    private var leadingComments: MutableList<Comment>? = null

    /** Trailing comments collected after the previous token. */
    private var trailingComments: MutableList<Comment>? = null

    /** Whether a line break was encountered between the previous and current token. */
    private var precedingLineBreak: Boolean = false

    /** Whether any token has been scanned yet (to handle leading comments at start of file). */
    private var hasScannedToken: Boolean = false

    // -- Public getters -------------------------------------------------------

    /** Returns the [SyntaxKind] of the current token. */
    fun getToken(): SyntaxKind = token

    /** Returns the source text of the current token. */
    fun getTokenText(): String = text.substring(tokenPos, pos)

    /** Returns the start position of the current token. */
    fun getTokenPos(): Int = tokenPos

    /** Returns the current scan position (one past the end of the current token). */
    fun getPos(): Int = pos

    /**
     * Returns the resolved value of the current token. For string literals this is
     * the unescaped content (without quotes). For numeric literals this is the
     * canonical numeric text. For identifiers and keywords this is the identifier text.
     */
    fun getTokenValue(): String = tokenValue

    /** Returns comments that appeared before the current token, or `null`. */
    fun getLeadingComments(): List<Comment>? = leadingComments

    /** Returns comments that appeared after the previous token on the same line, or `null`. */
    fun getTrailingComments(): List<Comment>? = trailingComments

    /**
     * Returns and clears the trailing comments. Used to "consume" trailing comments so
     * that a subsequent caller (e.g. [parsePrimaryExpression]'s inlineCmts) does not
     * capture the same comments a second time.
     */
    fun consumeTrailingComments(): List<Comment>? = trailingComments.also { trailingComments = null }

    /** Returns `true` if a line break was found between the previous and current token. */
    fun hasPrecedingLineBreak(): Boolean = precedingLineBreak

    /** Returns the full source text being scanned. */
    fun getSourceText(): String = text

    // -- Look-ahead -----------------------------------------------------------

    /**
     * Executes [callback] speculatively. The scanner state is saved before the call
     * and restored afterwards regardless of the result. Returns whatever [callback] returns.
     */
    fun <T> lookAhead(callback: () -> T): T {
        val savedPos = pos
        val savedToken = token
        val savedTokenPos = tokenPos
        val savedTokenValue = tokenValue
        val savedPrecedingLineBreak = precedingLineBreak
        val savedLeadingComments = leadingComments
        val savedTrailingComments = trailingComments
        val savedHasScannedToken = hasScannedToken
        try {
            return callback()
        } finally {
            pos = savedPos
            token = savedToken
            tokenPos = savedTokenPos
            tokenValue = savedTokenValue
            precedingLineBreak = savedPrecedingLineBreak
            leadingComments = savedLeadingComments
            trailingComments = savedTrailingComments
            hasScannedToken = savedHasScannedToken
        }
    }

    /**
     * Executes [callback] speculatively. If [callback] returns a non-null, truthy result
     * the scanner state is kept; otherwise it is reverted to the state before the call.
     */
    fun <T> tryScan(callback: () -> T): T {
        val savedPos = pos
        val savedToken = token
        val savedTokenPos = tokenPos
        val savedTokenValue = tokenValue
        val savedPrecedingLineBreak = precedingLineBreak
        val savedLeadingComments = leadingComments
        val savedTrailingComments = trailingComments
        val result = callback()
        if (result == null || result == false) {
            pos = savedPos
            token = savedToken
            tokenPos = savedTokenPos
            tokenValue = savedTokenValue
            precedingLineBreak = savedPrecedingLineBreak
            leadingComments = savedLeadingComments
            trailingComments = savedTrailingComments
        }
        return result
    }

    // -- Core scanning --------------------------------------------------------

    /**
     * Advances to the next token, skipping whitespace and collecting comments.
     * Returns the [SyntaxKind] of the scanned token.
     */
    fun scan(): SyntaxKind {
        leadingComments = null
        trailingComments = null
        precedingLineBreak = false

        // Skip whitespace and collect leading comments/trivia
        scanLeadingTrivia()

        tokenPos = pos
        tokenValue = ""

        if (pos >= end) {
            token = SyntaxKind.EndOfFile
            return token
        }

        val ch = text[pos]

        token = when {
            // Identifiers and keywords
            isIdentifierStart(ch) -> scanIdentifierOrKeyword()

            // Numeric literals
            isDigit(ch) -> scanNumericLiteral()

            // Dot can start a numeric literal (.5) or be an operator
            ch == '.' -> {
                if (pos + 1 < end && isDigit(text[pos + 1])) {
                    scanNumericLiteral()
                } else if (pos + 2 < end && text[pos + 1] == '.' && text[pos + 2] == '.') {
                    pos += 3
                    tokenValue = "..."
                    SyntaxKind.DotDotDot
                } else {
                    pos++
                    tokenValue = "."
                    SyntaxKind.Dot
                }
            }

            // String literals
            ch == '"' || ch == '\'' -> scanStringLiteral()

            // Template literals
            ch == '`' -> scanTemplateLiteral()

            // Operators and punctuation
            else -> scanPunctuationOrOperator()
        }

        return token
    }

    /**
     * Re-scans the current token as a template continuation (after a `}`).
     * This is called by the parser when it encounters `}` inside a template expression
     * and needs to continue scanning the template literal.
     */
    fun reScanTemplateToken(): SyntaxKind {
        // Reset position to the start of the current token (which should be `}`)
        pos = tokenPos
        token = scanTemplateMiddleOrTail()
        return token
    }

    /**
     * Re-scans the current `/` or `/=` token as a regular expression literal.
     * Called by the parser when it determines the `/` starts a regex.
     */
    fun reScanSlashToken(): SyntaxKind {
        // Reset to the start of the slash token
        pos = tokenPos
        if (pos < end && text[pos] == '/') {
            pos++ // skip opening /
            var inCharClass = false
            while (pos < end) {
                val c = text[pos]
                if (c == '\\') {
                    pos++ // skip escape
                    if (pos < end) pos++
                    continue
                }
                if (c == '[') {
                    inCharClass = true
                } else if (c == ']') {
                    inCharClass = false
                } else if (c == '/' && !inCharClass) {
                    break
                } else if (isLineBreak(c)) {
                    // Unterminated regex
                    break
                }
                pos++
            }
            if (pos < end && text[pos] == '/') {
                pos++ // skip closing /
                // Scan flags
                while (pos < end && isIdentifierPart(text[pos])) {
                    pos++
                }
            }
            tokenValue = text.substring(tokenPos, pos)
            token = SyntaxKind.RegularExpressionLiteral
        }
        return token
    }

    /**
     * Re-scans `>>`, `>>=`, `>>>`, or `>>>=` as a single `>` token.
     * Used by the parser when it expects a closing `>` for type parameters/arguments.
     */
    fun reScanGreaterToken(): SyntaxKind {
        if (token == SyntaxKind.GreaterThanGreaterThan ||
            token == SyntaxKind.GreaterThanGreaterThanGreaterThan ||
            token == SyntaxKind.GreaterThanGreaterThanEquals ||
            token == SyntaxKind.GreaterThanGreaterThanGreaterThanEquals
        ) {
            pos = tokenPos + 1
            tokenValue = ">"
            token = SyntaxKind.GreaterThan
            return token
        }
        return token
    }

    // -- Private scanning methods ---------------------------------------------

    private fun scanLeadingTrivia() {
        // At the start of the file (before any token has been scanned),
        // treat all comments as leading comments since there's no previous token
        var seenLineBreak = !hasScannedToken
        hasScannedToken = true
        while (pos < end) {
            val ch = text[pos]
            when {
                isWhitespace(ch) -> pos++
                isLineBreak(ch) -> {
                    precedingLineBreak = true
                    seenLineBreak = true
                    if (ch == '\r' && pos + 1 < end && text[pos + 1] == '\n') {
                        pos += 2
                    } else {
                        pos++
                    }
                }

                ch == '/' && pos + 1 < end -> {
                    val next = text[pos + 1]
                    when (next) {
                        '/' -> {
                            val commentStart = pos
                            pos += 2
                            while (pos < end && !isLineBreak(text[pos])) {
                                pos++
                            }
                            val hasTrailingNewLine = pos < end && isLineBreak(text[pos])
                            val comment = Comment(
                                kind = SyntaxKind.SingleLineComment,
                                text = text.substring(commentStart, pos),
                                pos = commentStart,
                                end = pos,
                                hasTrailingNewLine = hasTrailingNewLine,
                                hasPrecedingNewLine = seenLineBreak,
                            )
                            if (seenLineBreak) {
                                if (leadingComments == null) {
                                    leadingComments = mutableListOf()
                                }
                                leadingComments!!.add(comment)
                            } else {
                                if (trailingComments == null) {
                                    trailingComments = mutableListOf()
                                }
                                trailingComments!!.add(comment)
                            }
                        }

                        '*' -> {
                            val commentStart = pos
                            pos += 2
                            var terminated = false
                            while (pos < end) {
                                if (text[pos] == '*' && pos + 1 < end && text[pos + 1] == '/') {
                                    pos += 2
                                    terminated = true
                                    break
                                }
                                pos++
                            }
                            if (!terminated) {
                                // Unterminated multi-line comment -- just consume to end
                            }
                            val hasTrailingNewLine = pos < end && isLineBreak(text[pos])
                            val comment = Comment(
                                kind = SyntaxKind.MultiLineComment,
                                text = text.substring(commentStart, pos),
                                pos = commentStart,
                                end = pos,
                                hasTrailingNewLine = hasTrailingNewLine,
                                hasPrecedingNewLine = seenLineBreak,
                            )
                            if (seenLineBreak) {
                                if (leadingComments == null) {
                                    leadingComments = mutableListOf()
                                }
                                leadingComments!!.add(comment)
                            } else {
                                if (trailingComments == null) {
                                    trailingComments = mutableListOf()
                                }
                                trailingComments!!.add(comment)
                            }
                        }

                        else -> return
                    }
                }

                // Conflict marker trivia: lines starting with <<<<<<<, =======, >>>>>>>
                // Skip <<<<<<<...HEAD line, then skip everything from ======= to >>>>>>> (inclusive).
                seenLineBreak && isConflictMarkerStart(ch) -> {
                    skipConflictMarkerTrivia()
                    seenLineBreak = true
                    precedingLineBreak = true
                }

                else -> return
            }
        }
    }

    /** Returns true if [ch] starts a conflict marker (`<`, `=`, `>`, `|`) */
    private fun isConflictMarkerStart(ch: Char): Boolean {
        if (ch != '<' && ch != '=' && ch != '>' && ch != '|') return false
        if (pos + 7 > end) return false
        // All 7 chars must be the same marker character (or '=')
        val c7 = text.substring(pos, pos + 7)
        return c7 == "<<<<<<<" || c7 == "=======" || c7 == ">>>>>>>" || c7 == "|||||||"
    }

    /**
     * Skips conflict marker trivia. When called at `<<<<<<<` or `>>>>>>>`, skips just that line.
     * When called at `=======` or `|||||||`, skips that line AND everything until (and including)
     * the next boundary marker (`>>>>>>>` for `=======`, `=======` for `|||||||`).
     */
    private fun skipConflictMarkerTrivia() {
        val markerChar = text[pos]
        // Skip the current marker line
        while (pos < end && !isLineBreak(text[pos])) pos++
        if (pos < end) {
            if (text[pos] == '\r' && pos + 1 < end && text[pos + 1] == '\n') pos += 2 else pos++
        }

        val endMarker = when (markerChar) {
            '=' -> ">>>>>>>"   // skip content between ======= and >>>>>>>
            '|' -> ">>>>>>>"   // skip content between ||||||| and >>>>>>> (includes ======= section)
            else -> return     // <<<<<<<, >>>>>>> — just skip the one line
        }

        // Skip lines until we find the end marker, then skip that marker line too
        while (pos < end) {
            if (pos + 7 <= end && text.substring(pos, pos + 7) == endMarker) {
                // Skip the end marker line
                while (pos < end && !isLineBreak(text[pos])) pos++
                if (pos < end) {
                    if (text[pos] == '\r' && pos + 1 < end && text[pos + 1] == '\n') pos += 2 else pos++
                }
                break
            }
            while (pos < end && !isLineBreak(text[pos])) pos++
            if (pos < end) {
                if (text[pos] == '\r' && pos + 1 < end && text[pos + 1] == '\n') pos += 2 else pos++
            }
        }
    }

    private fun scanIdentifierOrKeyword(): SyntaxKind {
        val start = pos
        pos++ // consume the first character (already verified as identifier start)
        while (pos < end && isIdentifierPart(text[pos])) {
            pos++
        }
        val word = text.substring(start, pos)
        tokenValue = word

        val keywordKind = KEYWORDS[word]
        return keywordKind ?: SyntaxKind.Identifier
    }

    private fun scanNumericLiteral(): SyntaxKind {
        val start = pos

        if (text[pos] == '0' && pos + 1 < end) {
            val next = text[pos + 1]
            when (next) {
                'x', 'X' -> return scanHexLiteral(start)
                'b', 'B' -> return scanBinaryLiteral(start)
                'o', 'O' -> return scanOctalLiteral(start)
            }
        }

        // Decimal literal
        scanDecimalDigits()

        // Fractional part
        if (pos < end && text[pos] == '.') {
            pos++
            scanDecimalDigits()
        }

        // Exponent part
        if (pos < end && (text[pos] == 'e' || text[pos] == 'E')) {
            pos++
            if (pos < end && (text[pos] == '+' || text[pos] == '-')) {
                pos++
            }
            scanDecimalDigits()
        }

        // BigInt suffix
        if (pos < end && text[pos] == 'n') {
            pos++
            tokenValue = text.substring(start, pos)
            return SyntaxKind.BigIntLiteral
        }

        tokenValue = text.substring(start, pos)
        return SyntaxKind.NumericLiteral
    }

    private fun scanDecimalDigits() {
        while (pos < end) {
            val ch = text[pos]
            if (isDigit(ch) || ch == '_') {
                pos++
            } else {
                break
            }
        }
    }

    private fun scanHexLiteral(start: Int): SyntaxKind {
        pos += 2 // skip 0x
        while (pos < end && (isHexDigit(text[pos]) || text[pos] == '_')) {
            pos++
        }
        if (pos < end && text[pos] == 'n') {
            pos++
            tokenValue = text.substring(start, pos)
            return SyntaxKind.BigIntLiteral
        }
        tokenValue = text.substring(start, pos)
        return SyntaxKind.NumericLiteral
    }

    private fun scanBinaryLiteral(start: Int): SyntaxKind {
        pos += 2 // skip 0b
        while (pos < end) {
            val ch = text[pos]
            if (ch == '0' || ch == '1' || ch == '_') {
                pos++
            } else {
                break
            }
        }
        if (pos < end && text[pos] == 'n') {
            pos++
            tokenValue = text.substring(start, pos)
            return SyntaxKind.BigIntLiteral
        }
        tokenValue = text.substring(start, pos)
        return SyntaxKind.NumericLiteral
    }

    private fun scanOctalLiteral(start: Int): SyntaxKind {
        pos += 2 // skip 0o
        while (pos < end) {
            val ch = text[pos]
            if (isOctalDigit(ch) || ch == '_') {
                pos++
            } else {
                break
            }
        }
        if (pos < end && text[pos] == 'n') {
            pos++
            tokenValue = text.substring(start, pos)
            return SyntaxKind.BigIntLiteral
        }
        tokenValue = text.substring(start, pos)
        return SyntaxKind.NumericLiteral
    }

    private fun scanStringLiteral(): SyntaxKind {
        val quote = text[pos]
        pos++ // skip opening quote
        val sb = StringBuilder()
        while (pos < end) {
            val ch = text[pos]
            if (ch == quote) {
                pos++ // skip closing quote
                break
            }
            if (ch == '\\') {
                pos++
                if (pos < end) {
                    val escaped = scanEscapeSequence()
                    sb.append(escaped)
                }
                continue
            }
            if (isLineBreak(ch)) {
                // Unterminated string literal
                break
            }
            sb.append(ch)
            pos++
        }
        tokenValue = sb.toString()
        return SyntaxKind.StringLiteral
    }

    private fun scanEscapeSequence(): String {
        val ch = text[pos]
        pos++
        return when (ch) {
            'n' -> "\n"
            't' -> "\t"
            'r' -> "\r"
            '\\' -> "\\"
            '\'' -> "'"
            '"' -> "\""
            '0' -> "\u0000"
            'b' -> "\b"
            'f' -> "\u000C"
            'v' -> "\u000B"
            'x' -> {
                // \xHH
                val hex = readHexChars(2)
                if (hex.isEmpty()) "\\x" else hex.toInt(16).toChar().toString()
            }

            'u' -> {
                if (pos < end && text[pos] == '{') {
                    // \u{HHHHHH}
                    pos++ // skip {
                    val sb = StringBuilder()
                    while (pos < end && text[pos] != '}') {
                        sb.append(text[pos])
                        pos++
                    }
                    if (pos < end) pos++ // skip }
                    val str = sb.toString()
                    if (str.isEmpty()) "\\u{}" else codePointToString(str.toInt(16))
                } else {
                    // \uHHHH
                    val hex = readHexChars(4)
                    if (hex.isEmpty()) "\\u" else hex.toInt(16).toChar().toString()
                }
            }

            '\r' -> {
                // Line continuation
                if (pos < end && text[pos] == '\n') pos++
                ""
            }

            '\n' -> "" // Line continuation
            else -> ch.toString()
        }
    }

    private fun readHexChars(count: Int): String {
        val sb = StringBuilder()
        var remaining = count
        while (remaining > 0 && pos < end && isHexDigit(text[pos])) {
            sb.append(text[pos])
            pos++
            remaining--
        }
        return sb.toString()
    }

    private fun scanTemplateLiteral(): SyntaxKind {
        pos++ // skip opening backtick
        val sb = StringBuilder()
        while (pos < end) {
            val ch = text[pos]
            if (ch == '`') {
                pos++ // skip closing backtick
                tokenValue = sb.toString()
                return SyntaxKind.NoSubstitutionTemplateLiteral
            }
            if (ch == '$' && pos + 1 < end && text[pos + 1] == '{') {
                pos += 2 // skip ${
                tokenValue = sb.toString()
                return SyntaxKind.TemplateHead
            }
            if (ch == '\\') {
                // Preserve raw escape sequences in template literals (do not decode them).
                // The JS engine decodes them at runtime; we emit the source as-is.
                sb.append('\\')
                pos++
                if (pos < end) {
                    sb.append(text[pos])
                    pos++
                }
                continue
            }
            if (ch == '\r') {
                pos++
                if (pos < end && text[pos] == '\n') pos++
                sb.append('\n')
                continue
            }
            sb.append(ch)
            pos++
        }
        // Unterminated template
        tokenValue = sb.toString()
        return SyntaxKind.NoSubstitutionTemplateLiteral
    }

    private fun scanTemplateMiddleOrTail(): SyntaxKind {
        pos++ // skip } (the closing brace of the template expression)
        val sb = StringBuilder()
        tokenPos = pos - 1 // include the } in the token range
        while (pos < end) {
            val ch = text[pos]
            if (ch == '`') {
                pos++
                tokenValue = sb.toString()
                return SyntaxKind.TemplateTail
            }
            if (ch == '$' && pos + 1 < end && text[pos + 1] == '{') {
                pos += 2
                tokenValue = sb.toString()
                return SyntaxKind.TemplateMiddle
            }
            if (ch == '\\') {
                // Preserve raw escape sequences in template literals (do not decode them).
                sb.append('\\')
                pos++
                if (pos < end) {
                    sb.append(text[pos])
                    pos++
                }
                continue
            }
            if (ch == '\r') {
                pos++
                if (pos < end && text[pos] == '\n') pos++
                sb.append('\n')
                continue
            }
            sb.append(ch)
            pos++
        }
        // Unterminated template
        tokenValue = sb.toString()
        return SyntaxKind.TemplateTail
    }

    private fun scanPunctuationOrOperator(): SyntaxKind {
        val ch = text[pos]
        pos++

        return when (ch) {
            '{' -> {
                tokenValue = "{"; SyntaxKind.OpenBrace
            }

            '}' -> {
                tokenValue = "}"; SyntaxKind.CloseBrace
            }

            '(' -> {
                tokenValue = "("; SyntaxKind.OpenParen
            }

            ')' -> {
                tokenValue = ")"; SyntaxKind.CloseParen
            }

            '[' -> {
                tokenValue = "["; SyntaxKind.OpenBracket
            }

            ']' -> {
                tokenValue = "]"; SyntaxKind.CloseBracket
            }

            ';' -> {
                tokenValue = ";"; SyntaxKind.Semicolon
            }

            ',' -> {
                tokenValue = ","; SyntaxKind.Comma
            }

            '~' -> {
                tokenValue = "~"; SyntaxKind.Tilde
            }

            '@' -> {
                tokenValue = "@"; SyntaxKind.At
            }

            '#' -> {
                // # can start a private identifier
                if (pos < end && isIdentifierStart(text[pos])) {
                    // Scan private identifier as a single token
                    while (pos < end && isIdentifierPart(text[pos])) {
                        pos++
                    }
                    tokenValue = text.substring(tokenPos, pos)
                    SyntaxKind.Identifier
                } else {
                    tokenValue = "#"
                    SyntaxKind.Hash
                }
            }

            '?' -> {
                if (pos < end && text[pos] == '.') {
                    // ?. but not ?.digit (that would be ? followed by numeric literal)
                    if (pos + 1 >= end || !isDigit(text[pos + 1])) {
                        pos++
                        tokenValue = "?."
                        SyntaxKind.QuestionDot
                    } else {
                        tokenValue = "?"
                        SyntaxKind.Question
                    }
                } else if (pos < end && text[pos] == '?') {
                    pos++
                    if (pos < end && text[pos] == '=') {
                        pos++
                        tokenValue = "??="
                        SyntaxKind.QuestionQuestionEquals
                    } else {
                        tokenValue = "??"
                        SyntaxKind.QuestionQuestion
                    }
                } else {
                    tokenValue = "?"
                    SyntaxKind.Question
                }
            }

            ':' -> {
                tokenValue = ":"; SyntaxKind.Colon
            }

            '=' -> {
                if (pos < end && text[pos] == '=') {
                    pos++
                    if (pos < end && text[pos] == '=') {
                        pos++
                        tokenValue = "==="
                        SyntaxKind.EqualsEqualsEquals
                    } else {
                        tokenValue = "=="
                        SyntaxKind.EqualsEquals
                    }
                } else if (pos < end && text[pos] == '>') {
                    pos++
                    tokenValue = "=>"
                    SyntaxKind.EqualsGreaterThan
                } else {
                    tokenValue = "="
                    SyntaxKind.Equals
                }
            }

            '!' -> {
                if (pos < end && text[pos] == '=') {
                    pos++
                    if (pos < end && text[pos] == '=') {
                        pos++
                        tokenValue = "!=="
                        SyntaxKind.ExclamationEqualsEquals
                    } else {
                        tokenValue = "!="
                        SyntaxKind.ExclamationEquals
                    }
                } else {
                    tokenValue = "!"
                    SyntaxKind.Exclamation
                }
            }

            '+' -> {
                if (pos < end && text[pos] == '+') {
                    pos++
                    tokenValue = "++"
                    SyntaxKind.PlusPlus
                } else if (pos < end && text[pos] == '=') {
                    pos++
                    tokenValue = "+="
                    SyntaxKind.PlusEquals
                } else {
                    tokenValue = "+"
                    SyntaxKind.Plus
                }
            }

            '-' -> {
                if (pos < end && text[pos] == '-') {
                    pos++
                    tokenValue = "--"
                    SyntaxKind.MinusMinus
                } else if (pos < end && text[pos] == '=') {
                    pos++
                    tokenValue = "-="
                    SyntaxKind.MinusEquals
                } else {
                    tokenValue = "-"
                    SyntaxKind.Minus
                }
            }

            '*' -> {
                if (pos < end && text[pos] == '*') {
                    pos++
                    if (pos < end && text[pos] == '=') {
                        pos++
                        tokenValue = "**="
                        SyntaxKind.AsteriskAsteriskEquals
                    } else {
                        tokenValue = "**"
                        SyntaxKind.AsteriskAsterisk
                    }
                } else if (pos < end && text[pos] == '=') {
                    pos++
                    tokenValue = "*="
                    SyntaxKind.AsteriskEquals
                } else {
                    tokenValue = "*"
                    SyntaxKind.Asterisk
                }
            }

            '/' -> {
                // By the time we reach here, comments have already been handled in trivia scanning.
                // A `/` here is either division or the start of a regex (handled by reScanSlashToken).
                if (pos < end && text[pos] == '=') {
                    pos++
                    tokenValue = "/="
                    SyntaxKind.SlashEquals
                } else {
                    tokenValue = "/"
                    SyntaxKind.Slash
                }
            }

            '%' -> {
                if (pos < end && text[pos] == '=') {
                    pos++
                    tokenValue = "%="
                    SyntaxKind.PercentEquals
                } else {
                    tokenValue = "%"
                    SyntaxKind.Percent
                }
            }

            '<' -> {
                if (pos < end && text[pos] == '<') {
                    pos++
                    if (pos < end && text[pos] == '=') {
                        pos++
                        tokenValue = "<<="
                        SyntaxKind.LessThanLessThanEquals
                    } else {
                        tokenValue = "<<"
                        SyntaxKind.LessThanLessThan
                    }
                } else if (pos < end && text[pos] == '=') {
                    pos++
                    tokenValue = "<="
                    SyntaxKind.LessThanEquals
                } else {
                    tokenValue = "<"
                    SyntaxKind.LessThan
                }
            }

            '>' -> {
                if (pos < end && text[pos] == '>') {
                    pos++
                    if (pos < end && text[pos] == '>') {
                        pos++
                        if (pos < end && text[pos] == '=') {
                            pos++
                            tokenValue = ">>>="
                            SyntaxKind.GreaterThanGreaterThanGreaterThanEquals
                        } else {
                            tokenValue = ">>>"
                            SyntaxKind.GreaterThanGreaterThanGreaterThan
                        }
                    } else if (pos < end && text[pos] == '=') {
                        pos++
                        tokenValue = ">>="
                        SyntaxKind.GreaterThanGreaterThanEquals
                    } else {
                        tokenValue = ">>"
                        SyntaxKind.GreaterThanGreaterThan
                    }
                } else if (pos < end && text[pos] == '=') {
                    pos++
                    tokenValue = ">="
                    SyntaxKind.GreaterThanEquals
                } else {
                    tokenValue = ">"
                    SyntaxKind.GreaterThan
                }
            }

            '&' -> {
                if (pos < end && text[pos] == '&') {
                    pos++
                    if (pos < end && text[pos] == '=') {
                        pos++
                        tokenValue = "&&="
                        SyntaxKind.AmpersandAmpersandEquals
                    } else {
                        tokenValue = "&&"
                        SyntaxKind.AmpersandAmpersand
                    }
                } else if (pos < end && text[pos] == '=') {
                    pos++
                    tokenValue = "&="
                    SyntaxKind.AmpersandEquals
                } else {
                    tokenValue = "&"
                    SyntaxKind.Ampersand
                }
            }

            '|' -> {
                if (pos < end && text[pos] == '|') {
                    pos++
                    if (pos < end && text[pos] == '=') {
                        pos++
                        tokenValue = "||="
                        SyntaxKind.BarBarEquals
                    } else {
                        tokenValue = "||"
                        SyntaxKind.BarBar
                    }
                } else if (pos < end && text[pos] == '=') {
                    pos++
                    tokenValue = "|="
                    SyntaxKind.BarEquals
                } else {
                    tokenValue = "|"
                    SyntaxKind.Bar
                }
            }

            '^' -> {
                if (pos < end && text[pos] == '=') {
                    pos++
                    tokenValue = "^="
                    SyntaxKind.CaretEquals
                } else {
                    tokenValue = "^"
                    SyntaxKind.Caret
                }
            }

            else -> {
                tokenValue = ch.toString()
                SyntaxKind.Unknown
            }
        }
    }

    // -- Character classification utilities -----------------------------------

    companion object {

        /** Returns `true` if [ch] can start an identifier (letter, `_`, `$`, or Unicode letter). */
        fun isIdentifierStart(ch: Char): Boolean =
            ch in 'a'..'z' || ch in 'A'..'Z' || ch == '_' || ch == '$' || ch.isUnicodeIdentifierStart()

        /** Returns `true` if [ch] can continue an identifier (letter, digit, `_`, `$`, or Unicode). */
        fun isIdentifierPart(ch: Char): Boolean =
            ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_' || ch == '$' ||
                    ch.isUnicodeIdentifierPart()

        /** Returns `true` if [ch] is a whitespace character (excluding line breaks). */
        fun isWhitespace(ch: Char): Boolean = when (ch) {
            ' ', '\t', '\u000B', '\u000C', '\u00A0',
            '\u1680', '\u2000', '\u2001', '\u2002', '\u2003',
            '\u2004', '\u2005', '\u2006', '\u2007', '\u2008',
            '\u2009', '\u200A', '\u202F', '\u205F', '\u3000',
            '\uFEFF' -> true

            else -> false
        }

        /** Returns `true` if [ch] is a line break character. */
        fun isLineBreak(ch: Char): Boolean = when (ch) {
            '\n', '\r', '\u2028', '\u2029' -> true
            else -> false
        }

        /** Returns `true` if [ch] is an ASCII decimal digit. */
        fun isDigit(ch: Char): Boolean = ch in '0'..'9'

        /** Returns `true` if [ch] is a hexadecimal digit. */
        fun isHexDigit(ch: Char): Boolean =
            ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'

        /** Returns `true` if [ch] is an octal digit (0-7). */
        fun isOctalDigit(ch: Char): Boolean = ch in '0'..'7'

        /**
         * Converts a Unicode code point (including supplementary plane) to a [String].
         * Handles surrogate pair encoding for code points above U+FFFF.
         */
        fun codePointToString(codePoint: Int): String {
            return if (codePoint <= 0xFFFF) {
                codePoint.toChar().toString()
            } else {
                val offset = codePoint - 0x10000
                val high = ((offset shr 10) + 0xD800).toChar()
                val low = ((offset and 0x3FF) + 0xDC00).toChar()
                "$high$low"
            }
        }

        private fun Char.isUnicodeIdentifierStart(): Boolean =
            this.code > 127 && this.isLetter()

        private fun Char.isUnicodeIdentifierPart(): Boolean =
            this.code > 127 && (this.isLetterOrDigit() || this.category == CharCategory.NON_SPACING_MARK ||
                    this.category == CharCategory.COMBINING_SPACING_MARK ||
                    this.category == CharCategory.CONNECTOR_PUNCTUATION)
    }
}
