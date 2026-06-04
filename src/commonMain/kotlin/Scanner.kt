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

/** An octal or illegal escape sequence error found inside a string/template literal. */
data class StringEscapeError(
    val start: Int,    // source position of the `\`
    val length: Int,   // length of the full escape sequence
    val code: Int,     // 1487 (octal) or 1488 (illegal \8/\9)
    val message: String,
)

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

    /**
     * Position right after the end of the PREVIOUS token (before leading trivia of current token).
     * This is the raw source position at the start of [scan] before trivia is skipped.
     * Used by the JSX parser to know where to resume raw text scanning.
     */
    private var prevTokenEnd: Int = 0

    /** Whether the last scanned token had an invalid unicode escape sequence. */
    private var hasInvalidUnicodeEscape: Boolean = false

    /** Position of the invalid unicode escape within the source (for error reporting). */
    private var invalidUnicodeEscapePos: Int = -1

    /**
     * When an identifier starts with an invalid \uXXXX escape (decodes to non-identifier-start),
     * TypeScript strips the leading `\` from the emitted text. This field holds the corrected
     * raw text (source text with the leading `\` removed), or null if no correction is needed.
     * E.g., for `\u0031a` (decodes to `1a`, invalid start), correctedRawText = "u0031a".
     */
    private var correctedRawText: String? = null

    /** Whether the last scanned template literal token was unterminated. */
    private var tokenIsUnterminated: Boolean = false

    /** For unterminated string literals: true if the string ended right after a `\`
     * (no character followed the backslash before EOF). Used to distinguish TS1126
     * "Unexpected end of text" from TS1002 "Unterminated string literal". */
    private var stringEndedAfterBackslash: Boolean = false

    /** Returns whether the most-recent unterminated string literal ended after a backslash. */
    fun didStringEndAfterBackslash(): Boolean = stringEndedAfterBackslash

    /** True if the last scanned BigIntLiteral has an exponent (TS1352). */
    private var bigIntHasExponent: Boolean = false
    fun didBigIntHaveExponent(): Boolean = bigIntHasExponent

    /** True if the last scanned BigIntLiteral has a fractional part (TS1353). */
    private var bigIntHasFraction: Boolean = false
    fun didBigIntHaveFraction(): Boolean = bigIntHasFraction

    /** "binary", "octal", or "hex" if the last numeric/BigInt literal was a non-decimal
     * literal with NO digits after the prefix (e.g. `0bn`, `0on`, `0xn`).
     * Used to emit TS1177 (binary) / TS1178 (octal) / TS1125 (hex). */
    private var emptyDigitLiteralKind: String? = null
    fun getEmptyDigitLiteralKind(): String? = emptyDigitLiteralKind

    /** Source positions of trailing `_` separators in the last numeric/BigInt literal
     * (e.g. `123_n` → position of the `_`). Used to emit TS6188. */
    private var numericTrailingSeparatorPositions: List<Int> = emptyList()
    fun getNumericTrailingSeparatorPositions(): List<Int> = numericTrailingSeparatorPositions

    /** Source positions of `__` (consecutive separator) sequences in the last numeric/
     * BigInt literal. Used to emit TS6189. */
    private var numericDoubleSeparatorPositions: List<Int> = emptyList()
    fun getNumericDoubleSeparatorPositions(): List<Int> = numericDoubleSeparatorPositions

    /** Whether the last scanned numeric literal was a legacy octal (e.g. 01, 0123). */
    private var isLegacyOctalToken: Boolean = false

    /** Whether the last scanned numeric literal was a decimal with an invalid leading zero (e.g. 08, 09, 08.5). */
    private var isLeadingZeroDecimalToken: Boolean = false

    /**
     * Octal/illegal escape sequence errors found in the last scanned string/template literal.
     * Each entry is (escapeStart, escapeLength, code, message).
     * Code 1487 = octal escape, code 1488 = illegal escape (\8 or \9).
     */
    private val stringEscapeErrorList: MutableList<StringEscapeError> = mutableListOf()

    /** Returns (and clears) the escape errors from the last scanned string/template literal. */
    fun getAndClearStringEscapeErrors(): List<StringEscapeError> {
        if (stringEscapeErrorList.isEmpty()) return emptyList()
        val result = stringEscapeErrorList.toList()
        stringEscapeErrorList.clear()
        return result
    }

    /** Returns true if the last scanned identifier had an invalid unicode escape. */
    fun hasInvalidUnicodeEscapeInToken(): Boolean = hasInvalidUnicodeEscape

    /** Returns the source position of the invalid unicode escape, or -1 if none. */
    fun getInvalidUnicodeEscapePos(): Int = invalidUnicodeEscapePos

    /**
     * Returns the corrected raw text for identifiers with an invalid leading \uXXXX escape,
     * or null if no correction is needed. When non-null, use this instead of getTokenText()
     * as the rawText for the identifier (strips the leading backslash).
     */
    fun getCorrectedRawText(): String? = correctedRawText

    /** Returns true if the last scanned template literal was unterminated. */
    fun isTokenUnterminated(): Boolean = tokenIsUnterminated

    /** Returns true if the last scanned numeric literal was a legacy octal literal (e.g., 01, 0123). */
    fun isLegacyOctalLiteralToken(): Boolean = isLegacyOctalToken

    /** Returns true if the last scanned numeric literal was a decimal with an invalid leading zero (e.g., 08, 09). */
    fun isLeadingZeroDecimalLiteralToken(): Boolean = isLeadingZeroDecimalToken

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

    /**
     * Returns the source position right after the END of the previous token,
     * before any trivia (whitespace, comments) of the current token was skipped.
     * Used by the JSX parser to resume raw text scanning after parsing a child element.
     */
    fun getPrevTokenEnd(): Int = prevTokenEnd

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
        val savedPrevTokenEnd = prevTokenEnd
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
            prevTokenEnd = savedPrevTokenEnd
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
        val savedPrevTokenEnd = prevTokenEnd
        val result = callback()
        if (result == null) {
            pos = savedPos
            token = savedToken
            tokenPos = savedTokenPos
            tokenValue = savedTokenValue
            precedingLineBreak = savedPrecedingLineBreak
            leadingComments = savedLeadingComments
            trailingComments = savedTrailingComments
            prevTokenEnd = savedPrevTokenEnd
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
        tokenIsUnterminated = false
        stringEndedAfterBackslash = false
        bigIntHasExponent = false
        bigIntHasFraction = false
        emptyDigitLiteralKind = null
        numericTrailingSeparatorPositions = emptyList()
        numericDoubleSeparatorPositions = emptyList()
        isLegacyOctalToken = false
        isLeadingZeroDecimalToken = false

        // Track position right after previous token (before any trivia)
        prevTokenEnd = pos

        // Skip whitespace and collect leading comments/trivia
        scanLeadingTrivia()

        tokenPos = pos
        tokenValue = ""
        hasInvalidUnicodeEscape = false
        invalidUnicodeEscapePos = -1
        correctedRawText = null

        if (pos >= end) {
            token = SyntaxKind.EndOfFile
            return token
        }

        val ch = text[pos]

        token = when {
            // Identifiers and keywords (including \uXXXX escape sequences at start,
            // and supplementary Unicode plane chars encoded as surrogate pairs)
            isIdentifierStart(ch) ||
                    (ch.isHighSurrogate() && pos + 1 < end && text[pos + 1].isLowSurrogate()) ||
                    (ch == '\\' && pos + 1 < end && text[pos + 1] == 'u' &&
                    pos + 2 < end && (text[pos + 2] == '{' || isUnicodeEscape4Hex(pos + 2))) -> scanIdentifierOrKeyword()

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
        tokenIsUnterminated = false
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
                // Scan flags — handle both BMP and non-BMP (surrogate-pair) Unicode identifier parts
                while (pos < end) {
                    val fc = text[pos]
                    if (fc.isHighSurrogate() && pos + 1 < end && text[pos + 1].isLowSurrogate()) {
                        pos += 2 // supplementary Unicode char (surrogate pair) — valid flag char
                    } else if (isIdentifierPart(fc)) {
                        pos++
                    } else {
                        break
                    }
                }
            } else {
                // Regex stopped at EOF or line break before finding closing `/` — unterminated.
                tokenIsUnterminated = true
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

    /**
     * Re-scans `<<` or `<<=` as a single `<` token. Used by the parser when it
     * expects an opening `<` for type arguments and the following type is itself
     * generic (e.g. `Modifier<<T>(x: T) => T>`).
     */
    fun reScanLessThanToken(): SyntaxKind {
        if (token == SyntaxKind.LessThanLessThan ||
            token == SyntaxKind.LessThanLessThanEquals
        ) {
            pos = tokenPos + 1
            tokenValue = "<"
            token = SyntaxKind.LessThan
            return token
        }
        return token
    }

    /**
     * Resets the scanner to a specific position in the source text without scanning any token.
     * After this call, [getToken] returns [SyntaxKind.Unknown] and [getPos]/[getTokenPos] return [newPos].
     * Typically followed by a call to [scanJsxText] or [scan].
     */
    fun resetToPosition(newPos: Int) {
        pos = newPos
        tokenPos = newPos
        token = SyntaxKind.Unknown
        tokenValue = ""
    }

    /**
     * Scans JSX text content starting from the current [pos] until `<`, `{`, or EOF.
     * After this call, [getToken] returns the delimiter token (LessThan, OpenBrace, or EndOfFile)
     * and [tokenValue]/[getTokenText] contain the raw text found.
     * Returns the raw text scanned.
     */
    fun scanJsxText(): String {
        tokenPos = pos
        val start = pos
        while (pos < end) {
            val ch = text[pos]
            if (ch == '<' || ch == '{') break
            pos++
        }
        val result = text.substring(start, pos)
        tokenValue = result
        // Now scan the delimiter as the current token
        if (pos >= end) {
            token = SyntaxKind.EndOfFile
        } else {
            // We don't advance past the delimiter — just set token kind
            // so the caller can call scan() to actually consume it.
            // But to keep state consistent with the rest of the parser
            // (which uses nextToken() → scanner.scan()), we need to set
            // token to something meaningful without consuming the delimiter.
            // Leave token as Unknown so the parser will call scan() next.
            token = SyntaxKind.Unknown
        }
        return result
    }

    // -- Private scanning methods ---------------------------------------------

    private fun scanLeadingTrivia() {
        // At the start of the file (before any token has been scanned),
        // treat all comments as leading comments since there's no previous token
        var seenLineBreak = !hasScannedToken
        hasScannedToken = true

        // Skip shebang (#!) at start of file
        if (pos == 0 && pos + 1 < end && text[0] == '#' && text[1] == '!') {
            pos = 2
            while (pos < end && !isLineBreak(text[pos])) {
                pos++
            }
            seenLineBreak = true
            precedingLineBreak = true
        }

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
                            // Check if there's a newline in whitespace after `*/`
                            var hasTrailingNewLine = false
                            var scanPos = pos
                            while (scanPos < end && (text[scanPos] == ' ' || text[scanPos] == '\t')) {
                                scanPos++
                            }
                            if (scanPos < end && isLineBreak(text[scanPos])) {
                                hasTrailingNewLine = true
                            }
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

    /** Returns true if `\uXXXX` or `\u{XXXX}` escape appears to be valid (has hex digits or `{`) at current pos. */
    private fun looksLikeUnicodeEscape(): Boolean {
        // pos is at the char after 'u' (i.e., the third char of \uX...)
        return pos < end && (text[pos] == '{' || isHexDigit(text[pos]))
    }

    /**
     * Returns true if the text starting at [startPos] contains exactly 4 hex digits,
     * suitable for a `\uXXXX` escape (non-brace form).
     * Used to distinguish valid 4-digit unicode escapes from incomplete sequences like `\u003`.
     */
    private fun isUnicodeEscape4Hex(startPos: Int): Boolean =
        startPos + 3 < end &&
        isHexDigit(text[startPos]) &&
        isHexDigit(text[startPos + 1]) &&
        isHexDigit(text[startPos + 2]) &&
        isHexDigit(text[startPos + 3])

    private fun scanIdentifierOrKeyword(): SyntaxKind {
        val start = pos
        // Check if this identifier starts with a \uXXXX escape sequence
        if (pos < end && text[pos] == '\\') {
            return scanIdentifierWithEscapes(start)
        }
        // Consume first character — handle surrogate pairs for supplementary Unicode chars
        if (text[pos].isHighSurrogate() && pos + 1 < end && text[pos + 1].isLowSurrogate()) {
            pos += 2
        } else {
            pos++ // consume the first character (already verified as identifier start)
        }
        while (pos < end) {
            val ch = text[pos]
            // Handle surrogate pairs for supplementary Unicode plane identifier chars
            if (ch.isHighSurrogate() && pos + 1 < end && text[pos + 1].isLowSurrogate()) {
                pos += 2
                continue
            }
            // Only treat \u as mid-identifier escape if followed by '{' or exactly 4 hex digits.
            // Incomplete sequences like \u003 (3 hex digits) terminate the identifier — the \
            // will be scanned separately as an Unknown (invalid character) token.
            if (ch == '\\' && pos + 1 < end && text[pos + 1] == 'u') {
                val savedPos = pos
                pos += 2 // skip '\u' to check what follows
                val hasValidEscapeStart = pos < end && (text[pos] == '{' || isUnicodeEscape4Hex(pos))
                pos = savedPos // restore
                if (hasValidEscapeStart) {
                    return scanIdentifierWithEscapes(start)
                } else {
                    break // stop identifier at \u that has no valid 4-hex-digit escape following
                }
            }
            if (!isIdentifierPart(ch)) break
            pos++
        }
        val word = text.substring(start, pos)
        tokenValue = word

        val keywordKind = KEYWORDS[word]
        return keywordKind ?: SyntaxKind.Identifier
    }

    /**
     * Scans an identifier that contains at least one \uXXXX escape sequence.
     * The tokenValue is set to the decoded string (for keyword matching),
     * but the raw text (text.substring(start, pos)) is preserved for emit.
     */
    private fun scanIdentifierWithEscapes(start: Int): SyntaxKind {
        val sb = StringBuilder()
        // Include any already-scanned non-escape characters before the first escape
        val prefixLen = pos - start
        sb.append(text.substring(start, pos))
        var firstDecodedChar: Char? = null
        while (pos < end) {
            val ch = text[pos]
            when {
                ch == '\\' && pos + 1 < end && text[pos + 1] == 'u' -> {
                    val escapeStart = pos // position of '\'
                    pos += 2 // skip '\u'
                    val decoded = if (pos < end && text[pos] == '{') {
                        // \u{HHHH}
                        pos++ // skip '{'
                        val hexStart = pos
                        while (pos < end && isHexDigit(text[pos])) pos++
                        val hexStr = text.substring(hexStart, pos)
                        val hasClosingBrace = pos < end && text[pos] == '}'
                        if (hasClosingBrace) pos++ // skip '}'
                        if (hexStr.isEmpty() || !hasClosingBrace) {
                            hasInvalidUnicodeEscape = true
                            if (invalidUnicodeEscapePos < 0) invalidUnicodeEscapePos = escapeStart
                            if (hexStr.isEmpty()) "" else codePointToString(hexStr.toInt(16))
                        } else codePointToString(hexStr.toInt(16))
                    } else {
                        // \uHHHH - must have exactly 4 hex digits
                        val hexStart = pos
                        repeat(4) { if (pos < end && isHexDigit(text[pos])) pos++ }
                        val hexStr = text.substring(hexStart, pos)
                        if (hexStr.length != 4) {
                            hasInvalidUnicodeEscape = true
                            if (invalidUnicodeEscapePos < 0) invalidUnicodeEscapePos = escapeStart
                            if (hexStr.isEmpty()) "" else hexStr.toInt(16).toChar().toString()
                        } else hexStr.toInt(16).toChar().toString()
                    }
                    if (firstDecodedChar == null && prefixLen == 0 && sb.isEmpty() && decoded.isNotEmpty()) {
                        firstDecodedChar = decoded[0]
                    }
                    sb.append(decoded)
                }
                isIdentifierPart(ch) -> {
                    sb.append(ch)
                    pos++
                }
                else -> break
            }
        }
        // If the identifier starts with an escape and the decoded first char is not a valid identifier start, flag it
        // Special case: when the escape decoded to a supplementary-plane codepoint (encoded as a UTF-16 surrogate
        // pair), `firstDecodedChar` is the lone high surrogate and `isIdentifierStart(highSurrogate)` returns false
        // because surrogates aren't categorized as letters in isolation. Trust the `\u{HHHH}` escape syntax as
        // intentional for an astral identifier start (e.g. U+102A7 CARIAN LETTER A2). Without this carve-out the
        // leading `\` is stripped below and `this.\u{102A7}` mis-emits as `this.u{102A7}`.
        val firstIsAstralPair = prefixLen == 0 && sb.length >= 2 &&
                sb[0].isHighSurrogate() && sb[1].isLowSurrogate()
        if (prefixLen == 0 && firstDecodedChar != null && !firstIsAstralPair && !isIdentifierStart(firstDecodedChar)) {
            hasInvalidUnicodeEscape = true
            if (invalidUnicodeEscapePos < 0) invalidUnicodeEscapePos = tokenPos // start of escape at token start
            // TypeScript strips the leading `\` from the emitted text for this case.
            // E.g., `\u0031a` (decodes to `1a`, invalid start) should emit as `u0031a`.
            // Store the corrected raw text (source text with leading `\` removed).
            correctedRawText = text.substring(tokenPos + 1, pos)
        }
        tokenValue = sb.toString()
        val keywordKind = KEYWORDS[tokenValue]
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
        val digitStart = pos
        scanDecimalDigits()
        val scannedDigits = text.substring(digitStart, pos)

        // Check if this is a legacy octal (starts with '0', ≥2 chars, only octal digits 0-7, no separators).
        // Legacy octals must NOT consume a trailing '.' or 'e' — "00.5" splits into "00" + ".5" tokens.
        val isLegacyOctalLiteral = scannedDigits.length >= 2 &&
                scannedDigits[0] == '0' &&
                scannedDigits.all { it in '0'..'7' }

        // Check for decimal with invalid leading zero (e.g. 08, 09, 08.5) — not an octal,
        // but still has a leading '0' followed by more digits.
        val hasLeadingZeroDecimal = !isLegacyOctalLiteral &&
                scannedDigits.length >= 2 &&
                scannedDigits[0] == '0' &&
                scannedDigits[1].isDigit()

        var hasFraction = false
        var hasExponent = false
        if (!isLegacyOctalLiteral) {
            // Fractional part
            if (pos < end && text[pos] == '.') {
                pos++
                hasFraction = true
                scanDecimalDigits()
            }

            // Exponent part
            if (pos < end && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                hasExponent = true
                if (pos < end && (text[pos] == '+' || text[pos] == '-')) {
                    pos++
                }
                scanDecimalDigits()
            }
        }

        // Set diagnostic flags for legacy octals and leading-zero decimals
        if (isLegacyOctalLiteral) {
            isLegacyOctalToken = true
        } else if (hasLeadingZeroDecimal) {
            isLeadingZeroDecimalToken = true
        }

        // Numeric separator validation (TS6188 trailing, TS6189 double `__`) for the
        // body up to the optional `n` BigInt suffix.
        detectNumericSeparatorErrors(start, pos)

        // BigInt suffix — only for pure decimal (no legacy octal like 0123n, no float)
        // Legacy octal: starts with '0' followed by more digits (0123) — not a valid BigInt
        val isLegacyOctalCandidate = text[start] == '0' && (pos - start) > 1
        if (!isLegacyOctalCandidate && pos < end && text[pos] == 'n') {
            pos++
            // Track invalid-BigInt diagnostics: TS1352 if exponent, TS1353 if fraction
            bigIntHasExponent = hasExponent
            bigIntHasFraction = hasFraction
            tokenValue = text.substring(start, pos)
            return SyntaxKind.BigIntLiteral
        }

        tokenValue = text.substring(start, pos)
        return SyntaxKind.NumericLiteral
    }

    /**
     * Detect numeric separator errors in `text[start..endPos)`:
     * - TS6188 "Numeric separators are not allowed here." at trailing `_`
     *   (an `_` not followed by a digit / hex / etc.)
     * - TS6189 "Multiple consecutive numeric separators are not permitted." at
     *   any consecutive `__` sequence.
     *
     * Positions are added to `numericTrailingSeparatorPositions` /
     * `numericDoubleSeparatorPositions`; the parser flushes them after scanning.
     */
    private fun detectNumericSeparatorErrors(start: Int, endPos: Int) {
        val trailing = mutableListOf<Int>()
        val doubles = mutableListOf<Int>()
        var i = start
        while (i < endPos) {
            val ch = text[i]
            if (ch == '_') {
                val nextCh = if (i + 1 < endPos) text[i + 1] else ' '
                if (nextCh == '_') {
                    // The second `_` is the diagnostic position
                    doubles.add(i + 1)
                } else if (nextCh == ' ' || (!isHexDigit(nextCh) && nextCh != '.' && nextCh != 'e' && nextCh != 'E' && nextCh != '+' && nextCh != '-')) {
                    // Trailing `_` (no following digit-like char)
                    trailing.add(i)
                }
            }
            i++
        }
        if (trailing.isNotEmpty()) numericTrailingSeparatorPositions = trailing
        if (doubles.isNotEmpty()) numericDoubleSeparatorPositions = doubles
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
        val digitsStart = pos
        while (pos < end && (isHexDigit(text[pos]) || text[pos] == '_')) {
            pos++
        }
        if (pos == digitsStart) emptyDigitLiteralKind = "hex"
        detectNumericSeparatorErrors(digitsStart, pos)
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
        val digitsStart = pos
        while (pos < end) {
            val ch = text[pos]
            if (ch == '0' || ch == '1' || ch == '_') {
                pos++
            } else {
                break
            }
        }
        if (pos == digitsStart) emptyDigitLiteralKind = "binary"
        detectNumericSeparatorErrors(digitsStart, pos)
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
        val digitsStart = pos
        while (pos < end) {
            val ch = text[pos]
            if (isOctalDigit(ch) || ch == '_') {
                pos++
            } else {
                break
            }
        }
        if (pos == digitsStart) emptyDigitLiteralKind = "octal"
        detectNumericSeparatorErrors(digitsStart, pos)
        if (pos < end && text[pos] == 'n') {
            pos++
            tokenValue = text.substring(start, pos)
            return SyntaxKind.BigIntLiteral
        }
        tokenValue = text.substring(start, pos)
        return SyntaxKind.NumericLiteral
    }

    private fun scanStringLiteral(): SyntaxKind {
        stringEscapeErrorList.clear()
        val quote = text[pos]
        pos++ // skip opening quote
        val sb = StringBuilder()
        var terminated = false
        var endedAfterBackslash = false
        while (pos < end) {
            val ch = text[pos]
            if (ch == quote) {
                pos++ // skip closing quote
                terminated = true
                break
            }
            if (ch == '\\') {
                pos++
                if (pos < end) {
                    val escaped = scanEscapeSequence()
                    sb.append(escaped)
                } else {
                    endedAfterBackslash = true
                }
                continue
            }
            if (isLineBreak(ch)) {
                // Unterminated string literal — line break before closing quote
                break
            }
            sb.append(ch)
            pos++
        }
        if (!terminated) {
            tokenIsUnterminated = true
            stringEndedAfterBackslash = endedAfterBackslash
        }
        tokenValue = sb.toString()
        return SyntaxKind.StringLiteral
    }

    private fun scanEscapeSequence(): String {
        val backslashPos = pos  // position of the character AFTER '\'  (i.e. pos before consuming first escape char)
        val ch = text[pos]
        pos++
        return when (ch) {
            'n' -> "\n"
            't' -> "\t"
            'r' -> "\r"
            '\\' -> "\\"
            '\'' -> "'"
            '"' -> "\""
            '0' -> {
                // \0 followed by any digit → legacy octal attempt (TS1487), value starts at 0
                // \0 followed by non-digit → null character (valid, no error)
                if (pos < end && text[pos] in '0'..'9') {
                    // Legacy octal: \0 followed by any digit makes it an octal escape
                    val octalStart = backslashPos - 1  // position of '\'
                    var octalValue = 0
                    var octalLen = 1  // 1 digit so far ('0')
                    // Consume additional octal digits (0-7) up to total 3 digits, value ≤ 255
                    while (pos < end && text[pos] in '0'..'7' && octalLen < 3) {
                        val next = octalValue * 8 + (text[pos] - '0')
                        if (next > 255) break
                        octalValue = next
                        pos++
                        octalLen++
                    }
                    val hex = octalValue.toString(16).padStart(2, '0')
                    stringEscapeErrorList.add(StringEscapeError(
                        start = octalStart,
                        length = octalLen + 1,  // +1 for '\'
                        code = 1487,
                        message = "Octal escape sequences are not allowed. Use the syntax '\\x$hex'.",
                    ))
                    octalValue.toChar().toString()
                } else {
                    "\u0000"
                }
            }
            'b' -> "\b"
            'f' -> "\u000C"
            'v' -> "\u000B"
            'x' -> {
                // \xHH — must have exactly 2 hex digits
                val hex = readHexChars(2)
                if (hex.length < 2) {
                    // TS1125 "Hexadecimal digit expected." at position right after the
                    // consumed hex (or right after `\x` if none consumed).
                    stringEscapeErrorList.add(StringEscapeError(
                        start = pos, length = 0, code = 1125,
                        message = "Hexadecimal digit expected.",
                    ))
                }
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
                    // \uHHHH — must have exactly 4 hex digits
                    val hex = readHexChars(4)
                    if (hex.length < 4) {
                        // TS1125 "Hexadecimal digit expected." at position right after
                        // the consumed hex (or right after `\u` if none consumed).
                        stringEscapeErrorList.add(StringEscapeError(
                            start = pos, length = 0, code = 1125,
                            message = "Hexadecimal digit expected.",
                        ))
                    }
                    if (hex.isEmpty()) "\\u" else hex.toInt(16).toChar().toString()
                }
            }

            '\r' -> {
                // Line continuation
                if (pos < end && text[pos] == '\n') pos++
                ""
            }

            '\n' -> "" // Line continuation

            in '1'..'7' -> {
                // Octal escape: \1-\7, possibly followed by more octal digits (max 3 total, value ≤ 255)
                val octalStart = backslashPos - 1  // position of '\'
                var octalValue = ch - '0'
                var octalLen = 1  // 1 digit consumed
                // Consume up to 2 more octal digits (3 total max) while value ≤ 255
                while (pos < end && text[pos] in '0'..'7' && octalLen < 3) {
                    val next = octalValue * 8 + (text[pos] - '0')
                    if (next > 255) break
                    octalValue = next
                    pos++
                    octalLen++
                }
                val hex = octalValue.toString(16).padStart(2, '0')
                stringEscapeErrorList.add(StringEscapeError(
                    start = octalStart,
                    length = octalLen + 1,  // +1 for '\'
                    code = 1487,
                    message = "Octal escape sequences are not allowed. Use the syntax '\\x$hex'.",
                ))
                octalValue.toChar().toString()
            }

            '8', '9' -> {
                // Illegal escape: \8 or \9
                val octalStart = backslashPos - 1  // position of '\'
                stringEscapeErrorList.add(StringEscapeError(
                    start = octalStart,
                    length = 2,  // '\' + '8' or '9'
                    code = 1488,
                    message = "Escape sequence '\\$ch' is not allowed.",
                ))
                ch.toString()
            }

            else -> ch.toString()
        }
    }

    /**
     * Called when `\` is seen in a template literal (pos points at `\`).
     * Looks ahead to detect octal or illegal escape sequences and records errors.
     * Does NOT advance pos (the caller handles that).
     */
    private fun checkTemplateEscapeError() {
        val backslashPos = pos  // position of '\'
        if (pos + 1 >= end) return
        val ch = text[pos + 1]  // first char after '\'
        when {
            ch in '1'..'7' -> {
                // Octal: \1-\7, possibly followed by more octal digits while value ≤ 0xFF
                var octalValue = ch - '0'
                var octalLen = 1
                var lookahead = pos + 2
                while (lookahead < end && text[lookahead] in '0'..'7') {
                    val next = octalValue * 8 + (text[lookahead] - '0')
                    if (next > 255) break
                    octalValue = next
                    lookahead++
                    octalLen++
                }
                val hex = octalValue.toString(16).padStart(2, '0')
                stringEscapeErrorList.add(StringEscapeError(
                    start = backslashPos,
                    length = octalLen + 1,
                    code = 1487,
                    message = "Octal escape sequences are not allowed. Use the syntax '\\x$hex'.",
                ))
            }
            ch == '0' && pos + 2 < end && text[pos + 2] in '0'..'9' -> {
                // \0 followed by any digit → legacy octal attempt
                var octalValue = 0
                var octalLen = 1
                var lookahead = pos + 2
                while (lookahead < end && text[lookahead] in '0'..'7') {
                    val next = octalValue * 8 + (text[lookahead] - '0')
                    if (next > 255) break
                    octalValue = next
                    lookahead++
                    octalLen++
                }
                val hex = octalValue.toString(16).padStart(2, '0')
                stringEscapeErrorList.add(StringEscapeError(
                    start = backslashPos,
                    length = octalLen + 1,
                    code = 1487,
                    message = "Octal escape sequences are not allowed. Use the syntax '\\x$hex'.",
                ))
            }
            ch == '8' || ch == '9' -> {
                stringEscapeErrorList.add(StringEscapeError(
                    start = backslashPos,
                    length = 2,
                    code = 1488,
                    message = "Escape sequence '\\$ch' is not allowed.",
                ))
            }
            // B70.8: TS1125/TS1198 for hex/unicode escapes in template literals.
            // String literals already handle these via scanEscapeSequence; templates
            // preserve escapes raw and need separate validation.
            ch == 'x' -> {
                // \xHH must have exactly 2 hex digits after \x
                var hexCount = 0
                var probe = pos + 2 // position after \x
                while (probe < end && hexCount < 2 && isHexDigit(text[probe])) {
                    probe++
                    hexCount++
                }
                if (hexCount < 2) {
                    stringEscapeErrorList.add(StringEscapeError(
                        start = probe, length = 0, code = 1125,
                        message = "Hexadecimal digit expected.",
                    ))
                }
            }
            ch == 'u' -> {
                if (pos + 2 < end && text[pos + 2] == '{') {
                    // \u{HHHHHH...} — value must be ≤ 0x10FFFF
                    var probe = pos + 3 // position after \u{
                    val hexStart = probe
                    while (probe < end && isHexDigit(text[probe])) probe++
                    if (probe > hexStart) {
                        val hexText = text.substring(hexStart, probe)
                        // Parse as long to avoid overflow on >8 hex digits
                        val codePoint = try { hexText.toLong(16) } catch (_: NumberFormatException) { -1L }
                        if (codePoint > 0x10FFFFL) {
                            stringEscapeErrorList.add(StringEscapeError(
                                start = hexStart, length = (probe - hexStart),
                                code = 1198,
                                message = "An extended Unicode escape value must be between 0x0 and 0x10FFFF inclusive.",
                            ))
                        }
                    } else {
                        // Empty `\u{}` — TS1125
                        stringEscapeErrorList.add(StringEscapeError(
                            start = hexStart, length = 0, code = 1125,
                            message = "Hexadecimal digit expected.",
                        ))
                    }
                } else {
                    // \uHHHH — must have exactly 4 hex digits
                    var hexCount = 0
                    var probe = pos + 2 // position after \u
                    while (probe < end && hexCount < 4 && isHexDigit(text[probe])) {
                        probe++
                        hexCount++
                    }
                    if (hexCount < 4) {
                        stringEscapeErrorList.add(StringEscapeError(
                            start = probe, length = 0, code = 1125,
                            message = "Hexadecimal digit expected.",
                        ))
                    }
                }
            }
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
        stringEscapeErrorList.clear()
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
                // Check for octal/illegal escape sequences and record errors
                checkTemplateEscapeError()
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
        tokenIsUnterminated = true
        tokenValue = sb.toString()
        return SyntaxKind.NoSubstitutionTemplateLiteral
    }

    private fun scanTemplateMiddleOrTail(): SyntaxKind {
        stringEscapeErrorList.clear()
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
                // Check for octal/illegal escape sequences and record errors
                checkTemplateEscapeError()
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
                val nextCh = if (pos < end) text[pos] else '\u0000'
                val nextIsIdentStart = isIdentifierStart(nextCh) ||
                        (nextCh.isHighSurrogate() && pos + 1 < end && text[pos + 1].isLowSurrogate())
                // A private identifier body may BEGIN with a unicode escape (e.g. `#` + `x`) —
                // in which case nextIsIdentStart is false because the next char is the backslash.
                // Recognize it so the escape is decoded into the private name rather than split
                // into a bare Hash token + a separate identifier.
                val nextIsEscapeStart = nextCh == '\\' && pos + 1 < end && text[pos + 1] == 'u' &&
                        pos + 2 < end && (text[pos + 2] == '{' || isUnicodeEscape4Hex(pos + 2))
                if (nextIsIdentStart || nextIsEscapeStart) {
                    // Scan private identifier as a single token — handle surrogate pairs and
                    // mid-name unicode escapes (e.g. `#x` + `x`).
                    var hasEscape = false
                    while (pos < end) {
                        val c = text[pos]
                        if (c.isHighSurrogate() && pos + 1 < end && text[pos + 1].isLowSurrogate()) {
                            pos += 2
                        } else if (c == '\\' && pos + 1 < end && text[pos + 1] == 'u' &&
                                pos + 2 < end && (text[pos + 2] == '{' || isUnicodeEscape4Hex(pos + 2))) {
                            hasEscape = true
                            break
                        } else if (isIdentifierPart(c)) {
                            pos++
                        } else {
                            break
                        }
                    }
                    if (hasEscape) {
                        // Decode the escaped body so tokenValue matches a non-escaped use of the
                        // same name (the escaped and plain spellings are the same private member),
                        // while getTokenText() still returns the raw source for emit.
                        scanIdentifierWithEscapes(tokenPos + 1) // body starts right after '#'
                        tokenValue = "#" + tokenValue
                    } else {
                        tokenValue = text.substring(tokenPos, pos)
                    }
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
            ' ', '\t', '\u000B', '\u000C', '\u0085', '\u00A0',
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
            this.code > 127 && (this.isLetter() || this.category == CharCategory.LETTER_NUMBER)

        private fun Char.isUnicodeIdentifierPart(): Boolean =
            this.code > 127 && (this.isLetterOrDigit() || this.category == CharCategory.LETTER_NUMBER ||
                    this.category == CharCategory.NON_SPACING_MARK ||
                    this.category == CharCategory.COMBINING_SPACING_MARK ||
                    this.category == CharCategory.CONNECTOR_PUNCTUATION)
    }
}
