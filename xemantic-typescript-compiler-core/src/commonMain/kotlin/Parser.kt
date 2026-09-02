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
    // M1.13: per-file salt stamped onto every TypeParameter this parser creates, so the
    // checker's `typeParamInternCache` key can be made file-aware (see TypeParameter.internSalt).
    // Distinct fileNames → distinct salts → no cross-file pos collision; an empty fileName
    // (JSDoc sub-parses) salts to 0, which is fail-safe (same as the pre-fix pos-only key).
    private val typeParamFileSalt: Int = fileName.hashCode()
    private var token: SyntaxKind = SyntaxKind.Unknown
    private var prevToken: SyntaxKind = SyntaxKind.Unknown
    private val diagnostics = mutableListOf<Diagnostic>()
    private var inAsyncContext = topLevelAwait
    // tsc reparseTopLevelAwait: tracks function nesting so a TOP-LEVEL AwaitExpression can be
    // detected (the reparse's direct-parseStatement loop changes stray-`}` recovery — TS1109
    // ExpressionStatement(missing) instead of the SourceElements TS1128-skip).
    private var functionLikeDepth = 0
    private var sawTopLevelAwaitExpr = false
    private var disallowIn = false
    // tsc parses a PARENTHESIZED arrow (`(params) => body`) only at the ASSIGNMENT-expression
    // level, NOT as a binary/relational operand — so `a << (x) => y` is `(a << (x))` + leftover
    // `=> y`, and `<<T>(x:T) => T>f` (ambiguousGenericAssertion1) doesn't arrow the `(x:T)`.
    // TRUE at parseAssignmentExpression entry (leftmost operand), FALSE for binary RIGHT operands.
    private var parenArrowAllowed = true
    // tsc parseDelimitedList(ArrayBindingElements) abort: set by the for-init var-decl-list
    // recovery so a binding pattern started as a mis-recovered subsequent declarator
    // (`for (let of [1,2,3])` → `[]`) ABORTS on the first non-binding-element token
    // (a numeric/string literal is a valid enclosing-context statement start) instead of
    // consuming it. Read+reset at parseArrayBindingPattern entry so nested patterns don't inherit.
    private var bindingPatternAbortMode = false

    /**
     * tsc NodeFlags.DecoratorContext: true while parsing a decorator EXPRESSION.
     * In this context `[` is NOT parsed as an element access (it could be the start
     * of a ComputedPropertyName: `@x ["property"]: any;`). Reset by parseArgumentList
     * (tsc doOutsideOfContext) so `@dec(arr[0])` parses inner element accesses.
     */
    private var inDecoratorContext = false

    /**
     * tsc mustBeUnary: true while parsing a JSX element that is the OPERAND of a unary
     * expression (`!<Foo/>`, `typeof <a/>`, …) — the missing-comma `<sibling/>` recovery
     * must NOT fire there (the synthesized binary is not a valid UnaryExpression).
     * Set by the unary operand sites, CONSUMED at parseUnaryExpression entry.
     */
    private var jsxMustBeUnary = false
    /**
     * True while parsing a heritage-clause base expression (`extends A<T>, B`). In this
     * context a `Foo<T>` followed by a comma/colon/`&`/`|` (any `canFollowTypeArgumentsInExpression`
     * token) must NOT be converted into a value-position instantiation expression (which drops
     * the type arguments — the documented multi-base-generic misparse). Instead the postfix
     * `<` branch bails so `parseExpressionWithTypeArguments` re-parses the type args as heritage
     * type arguments, matching tsc (whose `parseLeftHandSideExpressionOrHigher` produces an
     * ExpressionWithTypeArguments that heritage returns verbatim). Reset by parseArgumentList so
     * a nested `extends foo(bar<T>)` call argument parses instantiation exprs normally.
     */
    private var parsingHeritageBase = false
    private var classBodyDepth = 0
    private var inTypeArgsDepth = 0
    private var inTupleTypeDepth = 0
    /** Round 452: set when [parseType]'s trailing-`?` recovery consumes a tuple element's
     *  optional marker (`[number?]`), so [parseTupleType] can record the optionality that
     *  would otherwise be silently discarded. Read+reset per element (so a nested tuple's
     *  marker does not leak to an enclosing element). */
    private var tupleElementConsumedOptionalMarker = false
    private var jsxElementDepth = 0
    /** expressionWithJSDocTypeArguments: set true when a JSDoc-`?` (nullable) type was recovered
     *  inside a type-argument list (`foo<?string>`), so a value-position instantiation paren can
     *  preserve the raw `<...>` text in JS emit. Reset before each `tryParseTypeArguments`. */
    private var sawJsDocInTypeArgs = false

    /** Stack of opening token positions for related-info on missing close tokens. */
    private val openTokenStack = mutableListOf<Int>()
    // B18.1: set true by parseInterfaceMembers when it bails out on a
    // top-level var/let/const inside an interface body — the caller skips
    // its parseExpected(CloseBrace) so TS1131 (already emitted) isn't
    // followed by a redundant TS1005.
    private var interfaceMembersBailedOnKeyword = false

    // B324: set true by parseParameterList when the list ABORTED — either the opening '('
    // was missing (tsc parseParameters returns an empty missing list, nothing consumed) or
    // a RESERVED keyword sat in parameter-name position (TS1390 + abort, token unconsumed).
    // parseFunctionDeclarationOrExpression consults it to give the function a MISSING body
    // (tsc parseFunctionBlock after a failed '{' — drives TS7010 + the `{ }` emit) instead
    // of a null body when the same-line follow token isn't '{'.
    private var paramListAborted = false

    // True while parsing the body of a type literal (`{ ... }` in a type position) as opposed
    // to an interface body. Used to pick TS1247 ("A type literal property cannot have an
    // initializer.") vs TS1246 ("An interface property cannot have an initializer.").
    private var inTypeLiteralForErrorWording = false

    /** True if the file uses JSX syntax (`.tsx` or `.jsx`, or forcibly enabled). */
    private val isJsxFile = forceJsx || fileName.endsWith(".tsx") || fileName.endsWith(".jsx")

    /** True if the file is JS-like (`.js`/`.jsx`/`.cjs`/`.mjs`) — gates JSDoc `@type` interpretation. */
    private val isJsLikeFile = fileName.endsWith(".js") || fileName.endsWith(".jsx") ||
        fileName.endsWith(".cjs") || fileName.endsWith(".mjs")

    /** Line start positions for line/character lookup — computed LAZILY on the
     *  first diagnostic that needs a line/col (M0.3(viii)): the eager per-parse
     *  computation was 5% of the self-compile's JFR samples, all wasted on clean
     *  parses (nothing else consumes it). Nullable-field pattern, not `by lazy`
     *  (the CLAUDE.md init-order gotcha + per-access sync cost). */
    private var lineStartsCache: IntArray? = null
    private val lineStarts: IntArray
        get() = lineStartsCache ?: computeLineStarts(source).also { lineStartsCache = it }

    fun parse(): SourceFile {
        // 16.0: Check triple-slash reference path directives for self-reference (TS1006).
        checkTripleSlashSelfReference()
        recordLeadingReferenceDirectives()
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
            moduleSpecifiers = moduleSpecifiers.toList(),
            referencedPaths = referencedPaths.toList(),
            referencedTypes = referencedTypes.toList(),
        ).also {
            it.typeAliasesWithTpDefaults = tpDefaultAliases.toList()
            indexSourceFile(it)
        }
    }

    fun getDiagnostics(): List<Diagnostic> = diagnostics.toList()

    // ── Module-specifier recording (tsc SourceFile.imports) ─────────────────

    /**
     * Module specifiers recorded during the parse — see [SourceFile.moduleSpecifiers].
     * Populated at the parse sites themselves (import/export declarations, dynamic
     * `import()` / `require()` calls, `import("...")` types, triple-slash reference
     * directives) so consumers get a lexically exact set without a tree walk.
     * A speculative (lookAhead/tryScan) parse may record — harmless over-collection:
     * the same text re-parses on the real path, so the specifier is real either way.
     */
    private val moduleSpecifiers = LinkedHashSet<String>()

    /** `/// <reference path="…" />` targets — see [SourceFile.referencedPaths]. */
    private val referencedPaths = LinkedHashSet<String>()

    /** `/// <reference types="…" />` targets — see [SourceFile.referencedTypes]. */
    private val referencedTypes = LinkedHashSet<String>()

    /** (M0.4 round 643) TypeAliasDeclarations with a TP default, recorded as they
     *  parse — see [SourceFile.typeAliasesWithTpDefaults]. Distinct node objects
     *  per parse call, so a speculative parse over-collects (filtered downstream
     *  by the detached-parent reach climb), never duplicates. */
    private val tpDefaultAliases = ArrayList<TypeAliasDeclaration>()

    private fun recordModuleSpecifier(node: Node?) {
        val text = (node as? StringLiteralNode)?.text ?: return
        if (text.isNotEmpty()) moduleSpecifiers.add(text)
    }

    /** Records `require("x")` (CJS) — dynamic `import("x")` is recorded at its dedicated parse site. */
    private fun recordCallModuleSpecifier(callee: Expression, args: List<Expression>) {
        if ((callee as? Identifier)?.text == "require") recordModuleSpecifier(args.firstOrNull())
    }

    /**
     * Records `/// <reference path|types="...">` directive specifiers from the file's
     * LEADING TRIVIA — blank lines, `//` lines, and `/* */` block comments before the
     * first code token (tsc honors directives after e.g. a block-comment copyright
     * header). Bounded to pre-code lines, so a string literal containing directive
     * text can never contribute. A `///` line inside a block comment is part of that
     * comment, not a directive. Kept separate from [checkTripleSlashSelfReference],
     * whose narrower leading-`///`-block scan is corpus-pinned for TS1084/TS1006.
     */
    private fun recordLeadingReferenceDirectives() {
        val directive = Regex("""^///\s*<reference\s+(path|types)\s*=\s*["']([^"']+)["']""")
        var inBlockComment = false
        for (rawLine in source.lineSequence()) {
            var line = rawLine.trimStart()
            if (inBlockComment) {
                val close = line.indexOf("*/")
                if (close < 0) continue
                line = line.substring(close + 2).trimStart()
                inBlockComment = false
            }
            // Consume any number of complete `/* ... */` comments on the line.
            while (line.startsWith("/*")) {
                val close = line.indexOf("*/", 2)
                if (close < 0) { inBlockComment = true; break }
                line = line.substring(close + 2).trimStart()
            }
            if (inBlockComment || line.isEmpty()) continue
            if (line.startsWith("//")) {
                directive.find(line)?.let { m ->
                    val target = m.groupValues[2]
                    // `path` and `types` resolve DIFFERENTLY (relative file vs type
                    // package), so they are kept apart rather than merged into
                    // moduleSpecifiers — see [SourceFile.referencedPaths] (M4.8).
                    if (target.isNotEmpty()) {
                        if (m.groupValues[1] == "path") referencedPaths.add(target)
                        else referencedTypes.add(target)
                    }
                }
                continue
            }
            break // first code token — directives past this point are plain comments
        }
    }

    // ── Infrastructure ──────────────────────────────────────────────────────

    /** True once the binary-file TS1490 has been reported (the scanner's U+FFFD marker). */
    private var binaryMarkerReported = false

    /** Token position where the current expression STATEMENT began — the missing-LHS
     *  `=`-skip recovery in parseAssignmentExpression applies only there (tsc's
     *  statement-level TS1128-skip), never in nested expression contexts. */
    private var exprStatementStartPos = -1

    /** True while the CURRENT statement was parsed from a FORCED position (an if/else/
     *  while/do/for/with/label body — tsc calls parseStatement there unconditionally,
     *  bypassing the list machinery's isStartOfStatement gate). tsc's statement-level
     *  `=`-skip / TS1128 recoveries never apply in forced positions — a then-statement
     *  starting at `^=` binds `<missing> ^= rhs` instead (constructorWithIncompleteTypeAnnotation).
     *  `inForcedStatementBody` is the hand-off from the body call site; parseStatement's
     *  dispatch consumes it into `exprStatementForced` so nested statements don't inherit it. */
    private var inForcedStatementBody = false
    private var exprStatementForced = false
    private val reportedHashBangs = mutableSetOf<Int>()

    /** Identifier texts tsc's parseErrorForMissingSemicolonAfter special-cases away
     *  from the generic TS1434 (they get TS1440/TS1435/keyword-specific messages or
     *  silence instead). */
    private val MISSING_SEMI_SPECIAL_IDENTS = setOf(
        "var", "let", "const", "declare", "interface", "is", "module", "namespace", "type",
    )

    /** tsc parser.ts `viableKeywordSuggestions` — every keyword longer than 2 chars,
     *  in textToKeywordObj order (the order matters for getSpaceSuggestion's
     *  first-prefix-wins rule). */
    private val VIABLE_KEYWORD_SUGGESTIONS = listOf(
        "abstract", "accessor", "any", "asserts", "assert", "bigint", "boolean", "break",
        "case", "catch", "class", "continue", "const", "debugger", "declare", "default",
        "defer", "delete", "else", "enum", "export", "extends", "false", "finally", "for",
        "from", "function", "get", "implements", "import", "infer", "instanceof",
        "interface", "intrinsic", "keyof", "let", "module", "namespace", "never", "new",
        "null", "number", "object", "package", "private", "protected", "public",
        "override", "out", "readonly", "require", "global", "return", "satisfies", "set",
        "static", "string", "super", "switch", "symbol", "this", "throw", "true", "try",
        "type", "typeof", "undefined", "unique", "unknown", "using", "var", "void",
        "while", "with", "yield", "async", "await",
    )

    /** tsc core.ts levenshteinWithMax: insert/delete = 1, substitution = 2,
     *  case-only difference = 0.1. Returns null when the distance exceeds [max]. */
    private fun levenshteinWithMax(s1: String, s2: String, max: Double): Double? {
        var previous = DoubleArray(s2.length + 1) { it.toDouble() }
        var current = DoubleArray(s2.length + 1)
        val big = max + 0.01
        for (i in 1..s1.length) {
            val c1 = s1[i - 1]
            val minJ = if (i > max) kotlin.math.ceil(i - max).toInt().coerceAtLeast(1) else 1
            val maxJ = if (s2.length > max + i) (max + i).toInt() else s2.length
            current[0] = i.toDouble()
            var colMin = i.toDouble()
            for (j in 1 until minJ) current[j] = big
            for (j in minJ..maxJ) {
                val substitutionDistance =
                    if (s1[i - 1].lowercaseChar() == s2[j - 1].lowercaseChar()) previous[j - 1] + 0.1
                    else previous[j - 1] + 2.0
                val dist = if (c1 == s2[j - 1]) previous[j - 1]
                else minOf(previous[j] + 1.0, current[j - 1] + 1.0, substitutionDistance)
                current[j] = dist
                colMin = minOf(colMin, dist)
            }
            for (j in maxJ + 1..s2.length) current[j] = big
            if (colMin > max) return null
            val tmp = previous; previous = current; current = tmp
        }
        val res = previous[s2.length]
        return if (res > max) null else res
    }

    /** tsc core.ts getSpellingSuggestion over the viable keyword pool. */
    private fun getKeywordSpellingSuggestion(name: String): String? {
        val maximumLengthDifference = maxOf(2, name.length * 34 / 100)
        var bestDistance = name.length * 2 / 5 + 1.0 // floor(len * 0.4) + 1
        var bestCandidate: String? = null
        for (candidate in VIABLE_KEYWORD_SUGGESTIONS) {
            if (kotlin.math.abs(candidate.length - name.length) > maximumLengthDifference) continue
            if (candidate == name) continue
            if (candidate.length < 3 && candidate.lowercase() != name.lowercase()) continue
            val distance = levenshteinWithMax(name, candidate, bestDistance - 0.1) ?: continue
            bestDistance = distance
            bestCandidate = candidate
        }
        return bestCandidate
    }

    /** tsc parser.ts getSpaceSuggestion: `declareconst` → "declare const". */
    private fun getSpaceSuggestion(expressionText: String): String? {
        for (keyword in VIABLE_KEYWORD_SUGGESTIONS) {
            if (expressionText.length > keyword.length + 2 && expressionText.startsWith(keyword)) {
                return "$keyword ${expressionText.substring(keyword.length)}"
            }
        }
        return null
    }

    private fun nextToken(): SyntaxKind {
        prevToken = token
        token = scanner.scan()
        // tsc scanner: scanning a literal U+FFFD reports TS1490 "File appears to be
        // binary." at (0,0) AT SCAN TIME (so the same-start dedup interacts with the
        // surrounding diagnostics exactly as in tsc — e.g. corrupted_ts's follow-up
        // TS1128 at start 0 is suppressed by it, TransportStream_ts's at (1,4) is not).
        if (scanner.sawBinaryFileMarker && !binaryMarkerReported) {
            binaryMarkerReported = true
            reportError("File appears to be binary.", code = 1490,
                overrideStart = 0, overrideLength = 0)
        }
        // Mid-file `#!` (scanner flag-flush): TS18026 spanning both chars, once per
        // position (re-scans through lookAhead must not double-report).
        val hashBang = scanner.hashBangErrorPos
        if (hashBang >= 0 && hashBang !in reportedHashBangs) {
            reportedHashBangs.add(hashBang)
            reportError("'#!' can only be used at the start of a file.", code = 18026,
                overrideStart = hashBang, overrideLength = 2)
        }
        // An identifier BEGINNING with a unicode escape that decodes to a non-identifier-
        // start char: tsc reports TS1127 "Invalid character." (0-width) AT SCAN TIME, so
        // it lands BEFORE any same-position recovery diagnostic (a var-decl list's
        // ','-expected dedups against it — constructorWithIncompleteTypeAnnotation line 72).
        val invEsc = scanner.invalidEscapeIdentStartPos
        if (invEsc >= 0 && invEsc !in reportedInvalidEscapeStarts) {
            reportedInvalidEscapeStarts.add(invEsc)
            reportError("Invalid character.", code = 1127,
                overrideStart = invEsc, overrideLength = 0)
        }
        return token
    }

    private val reportedInvalidEscapeStarts = mutableSetOf<Int>()

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
        if (openPos < 0) {
            // Caller signals the OPEN token was never consumed (recovered statement) —
            // plain "expected" without the TS1007 related info; tsc's span covers the
            // whole offending token (`"foo"` in reservedWords2 line 2).
            reportError("'$closeToken' expected.", code = 1005,
                overrideLength = scanner.getTokenText().length.coerceAtLeast(1))
        } else {
            reportErrorWithRelatedInfo(
                "'$closeToken' expected.", 1005,
                "The parser expected to find a '$closeToken' to match the '$openToken' token here.",
                1007, openPos
            )
        }
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
        // Expression STATEMENTS take the tsc parseErrorForMissingSemicolonAfter tail
        // in parseExpressionStatement instead (broadened for non-Identifier exprs);
        // tsc-faithful full-width reporting for other statement kinds is opt-in via
        // parseSemicolonRequired.
        if ((token == SyntaxKind.Colon || token == SyntaxKind.Question ||
                token == SyntaxKind.EqualsGreaterThan ||
                token == SyntaxKind.NumericLiteral ||
                token == SyntaxKind.BigIntLiteral) && !scanner.hasPrecedingLineBreak()) {
            val len = when (token) {
                SyntaxKind.Colon, SyntaxKind.Question -> 1
                SyntaxKind.EqualsGreaterThan -> 2
                else -> scanner.getTokenText().length.coerceAtLeast(1)
            }
            reportError("';' expected.", code = 1005,
                overrideStart = scanner.getTokenPos(), overrideLength = len)
        }
    }

    /** tsc parseSemicolon = tryParseSemicolon || parseExpected(Semicolon): reports TS1005
     *  "';' expected." at ANY non-ASI token (span = the token text, same-start-deduped),
     *  without consuming. Used at call sites verified to need the faithful behavior. */
    private fun parseSemicolonRequired() {
        if (token == SyntaxKind.Semicolon) {
            nextToken(); return
        }
        if (!canParseSemicolon()) {
            reportError("';' expected.", code = 1005,
                overrideStart = scanner.getTokenPos(),
                overrideLength = scanner.getTokenText().length.coerceAtLeast(1))
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

    /**
     * B397: TS2880 — `assert` in an import-CALL attributes object
     * (`import(spec, { assert: {...} })`, type or value position) is deprecated; use `with`.
     * tsc reports at DIFFERENT spans for the two positions: a VALUE-position dynamic import
     * squiggles the `assert` keyword (length 6); a TYPE-position `import(...).X` squiggles the
     * assert clause's VALUE (the inner `{`, length 1). Emitted as a parser diagnostic (2880 is
     * in GRAMMAR_CLASS_CODES so it does not trigger the real-parse-diagnostic suppression).
     */
    private fun emitImportAttrAssertDeprecation(attrs: Expression?, typePosition: Boolean) {
        val obj = attrs as? ObjectLiteralExpression ?: return
        for (p in obj.properties) {
            if (p !is PropertyAssignment) continue
            val nameNode = p.name as? Identifier ?: continue
            if (nameNode.text != "assert") continue
            val (start, len) = if (typePosition) {
                p.initializer.pos to 1
            } else {
                nameNode.pos to 6
            }
            reportError(
                "Import assertions have been replaced by import attributes. Use 'with' instead of 'assert'.",
                code = 2880, overrideStart = start, overrideLength = len,
            )
        }
    }

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
        // Mirror reportError / tsc parseErrorAtPosition same-start dedup: when several
        // close-token-at-EOF recoveries fire at the SAME position (e.g. multiple unclosed
        // blocks at EOF), keep only the first (innermost) — its TS1007 related points at the
        // innermost open token, matching tsc (errorRecoveryWithDotFollowedByNamespaceKeyword).
        val lastDiag = diagnostics.lastOrNull()
        if (lastDiag != null && lastDiag.start == start) return
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

    /** Combine the scanner's same-line trailing + new-line leading comment buffers (in that
     *  order), as `parseArgumentList` does for call-inner comments. Returns null when both are
     *  empty. Used to capture comments INTERNAL to an element access (`a /*1*/[ /*2*/ x /*3*/ ]`)
     *  at the points between the object/`[`/argument/`]`. */
    private fun combineCommentBuffers(): List<Comment>? {
        val trailing = scanner.getTrailingComments()
        val leading = scanner.getLeadingComments()
        return when {
            trailing.isNullOrEmpty() -> leading?.ifEmpty { null }
            leading.isNullOrEmpty() -> trailing
            else -> trailing + leading
        }
    }

    /** Accumulator for comments INTERNAL to an import/export statement (between its tokens),
     *  set fresh at the top of parseImportDeclaration/parseExportDeclaration and appended to at
     *  each inter-token boundary (in source order) by [captureIeSlot]. The nested clause/specifier
     *  parse functions append to it too (they are only reached from import/export parsing).
     *  importExportInternalComments. */
    private var ieSlots: MutableList<List<Comment>?>? = null

    /** Append the current inter-token comment buffer as the next import/export internal-comment slot.
     *  Call immediately AFTER the parseExpected/nextToken/parse that consumed the preceding token so
     *  the scanner buffer holds the comments between it and the next token. No-op outside import/export. */
    private fun captureIeSlot() {
        ieSlots?.add(combineCommentBuffers())
    }

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
                    // tsc reparseTopLevelAwait: when the file is a module with TOP-LEVEL
                    // await, the reparse loop calls parseStatement DIRECTLY (no
                    // isStartOfStatement gate) — a stray `}` becomes an
                    // ExpressionStatement(missing) with TS1109 'Expression expected.'
                    // and prints as `;` (reachabilityChecksNoCrash1's trailing `}`).
                    if (sawTopLevelAwaitExpr) {
                        val mPos = scanner.getTokenPos()
                        reportError("Expression expected.", code = 1109)
                        nextToken()
                        stmts.add(ExpressionStatement(
                            expression = Identifier(text = "", pos = mPos, end = mPos),
                            pos = mPos, end = mPos,
                        ))
                        continue
                    }
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
            // B327 (tsc parseDeclaration MissingDeclaration): outside class bodies,
            // `static` followed by a same-line identifier consumes the modifier with
            // TS1128 at it and re-parses the rest (`static test()` → `test();`).
            if (classBodyDepth == 0 && token == SyntaxKind.StaticKeyword &&
                lookAhead { nextToken(); isIdentifier() && !scanner.hasPrecedingLineBreak() }
            ) {
                reportError("Declaration or statement expected.", code = 1128)
                nextToken()
                continue
            }
            // B325 (tsc parseStatements/isStartOfStatement): a ',' can never START a
            // statement — TS1128 "Declaration or statement expected." (same-start-deduped
            // against a preceding TS1144 at the same comma) + SKIP, never the comma-
            // expression path (which would swallow following declarations as operands).
            if (token == SyntaxKind.Comma) {
                reportError("Declaration or statement expected.", code = 1128)
                nextToken()
                continue
            }
            // A bare `.` can never START a statement (tsc isStartOfStatement → false, so the
            // SourceElements list skips it via nextToken) — TS1128 + skip, rather than parsing
            // it as `<missing>.name` (parseImportAttributesError's derailed leftover `.RequireInterface`
            // must re-parse as a fresh identifier `RequireInterface`, not a property access). A
            // numeric `.5` scans as NumericLiteral and `...`/`?.` are distinct tokens, so a Dot
            // token here is always a stray leading dot.
            if (token == SyntaxKind.Dot) {
                reportError("Declaration or statement expected.", code = 1128)
                nextToken()
                continue
            }
            // A bare `:` can never START a statement either (a label's colon is consumed by
            // parseStatement's identifier+colon path) — TS1128 + skip (tsc SourceElements
            // abortParsingListOrMoveToNextToken; reachabilityChecksNoCrash1's leftover
            // return-type `:` after the aborted signature re-parse).
            if (token == SyntaxKind.Colon) {
                reportError("Declaration or statement expected.", code = 1128)
                nextToken()
                continue
            }
            // tsc abortParsingListOrMoveToNextToken(BlockStatements): a RESERVED keyword that
            // cannot start a statement (`case` outside a switch, …) reports TS1128
            // "Declaration or statement expected." (same-start-deduped); the list ABORTS
            // unconsumed when an enclosing ClassMembers context claims the token
            // (`case  = bfs.STATEMENTS(4);` after a derailed try unwinds all the way out and
            // re-parses as a class PROPERTY — constructorWithIncompleteTypeAnnotation), else
            // the token is skipped and the list continues.
            if (isKeyword() && !isIdentifier() && !canStartStatementForRecovery() &&
                token != SyntaxKind.DefaultKeyword) {
                // (`default` keeps its own dispatch arm — tsc's SourceElements context error
                // for it is "'export' expected.", produced there.)
                reportError("Declaration or statement expected.", code = 1128)
                if (classBodyDepth > 0 && isClassMemberStartLookahead()) break
                nextToken()
                continue
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

    /** Parses a statement from a FORCED position (if/else/loop/with/label body) —
     *  see [inForcedStatementBody]. */
    private fun parseForcedStatement(): Statement? {
        val saved = inForcedStatementBody
        inForcedStatementBody = true
        try {
            return parseStatement()
        } finally {
            inForcedStatementBody = saved
        }
    }

    private fun parseStatement(): Statement? {
        // Consume the forced-position hand-off: only the statement dispatched RIGHT HERE
        // is forced; anything nested (block bodies, initializer sub-statements) is not.
        statementForcedAtDispatch = inForcedStatementBody
        inForcedStatementBody = false
        return parseStatementDispatch()
    }

    private var statementForcedAtDispatch = false

    private fun parseStatementDispatch(): Statement? = when (token) {
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
        // `using x = e;` / `await using x = e;` (TS 5.2).  Both heads are ordinary
        // identifiers otherwise, so the LOOKAHEAD is the whole of what makes these arms
        // additive: without it `const using = 1; using + 1;` becomes a declaration.
        UsingKeyword -> if (isUsingDeclaration()) parseVariableStatement() else parseStatementFallback()
        AwaitKeyword -> if (isAwaitUsingDeclaration()) parseVariableStatement() else parseStatementFallback()
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
        // tsc isStartOfStatement: 'catch' and 'finally' do not actually indicate that the
        // code is part of a statement, but are claimed so we gracefully parse them and
        // error ('try' expected.) — parseStatement routes both to parseTryStatement.
        CatchKeyword, FinallyKeyword -> parseTryStatement()
        DebuggerKeyword -> parseDebuggerStatement()
        ImportKeyword -> {
            // import( = dynamic import call; import. = import.meta — parse as expression
            val nextIsParen = scanner.lookAhead { scanner.scan(); scanner.getToken() == SyntaxKind.OpenParen }
            val nextIsDot = scanner.lookAhead { scanner.scan(); scanner.getToken() == SyntaxKind.Dot }
            // B22.1: when the token after `import` is clearly not a valid import-clause start
            // (e.g. `import 10;` — numeric/bigint/regex literal, or `import , {a}` — a comma),
            // emit TS1128 at the `import` keyword and skip only the keyword so the remainder
            // parses as a regular statement. This mirrors tsc `isDeclaration`'s ImportKeyword
            // case: `import` starts a declaration iff the next token is a String literal, `*`,
            // `{`, or an identifier-or-keyword (`import(` / `import.` are handled above as
            // expressions). Any other follower (`,`, punctuation, literals) → not a declaration
            // → `import` re-parses as its own (erroneous) statement and the rest recovers
            // (es6ImportNamedImportParsingError: `import , { a } from "x"` → `{ a; }` block).
            val nextIsInvalid = scanner.lookAhead {
                scanner.scan()
                val t = scanner.getToken()
                !(t == SyntaxKind.StringLiteral || t == SyntaxKind.Asterisk ||
                    t == SyntaxKind.OpenBrace || isIdentifierToken(t) || t.name.endsWith("Keyword"))
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
        // tsc isDeclaration: `interface` starts a declaration iff the next token is an
        // identifier-capable token ON THE SAME LINE (nextTokenIsIdentifierOnSameLine).
        // `interface void { }` / `interface { }` are EXPRESSION statements (the
        // recovery in parseExpressionStatement adds TS2427/TS1438, the checker TS2693);
        // `interface interface { }` IS a declaration (strict-reserved words are
        // identifier-capable — the binder/checker owns the strict-mode complaint).
        InterfaceKeyword -> if (lookAhead { nextToken(); !scanner.hasPrecedingLineBreak() && isIdentifier() }) {
            parseInterfaceDeclaration()
        } else {
            parseExpressionStatement()
        }
        TypeKeyword -> if (isStartOfTypeAlias()) parseTypeAliasDeclaration() else parseExpressionStatement()
        EnumKeyword -> parseEnumDeclaration()
        // tsc isDeclaration: `namespace` starts a declaration iff followed by a
        // same-line identifier or string literal — `namespace void {}` is an
        // EXPRESSION statement (TS2819 from the recovery below + checker TS2304).
        NamespaceKeyword -> if (lookAhead {
                nextToken()
                !scanner.hasPrecedingLineBreak() && (isIdentifier() || token == SyntaxKind.StringLiteral)
            }) {
            parseModuleDeclaration()
        } else {
            parseExpressionStatement()
        }
        GlobalKeyword -> {
            // `global { }` / `global <identifier>` / `global export` start a global
            // augmentation declaration (tsc isStartOfDeclaration's GlobalKeyword case:
            // OpenBrace | Identifier | ExportKeyword); anything else (`global.x`,
            // `global = 5`) keeps `global` as an identifier expression.
            val isGlobalAug = lookAhead {
                nextToken()
                token == SyntaxKind.OpenBrace || token == SyntaxKind.Identifier ||
                    token == SyntaxKind.ExportKeyword
            }
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
            if (hasModifier && classBodyDepth > 0) {
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
        else -> parseStatementFallback()
    }

    /** The un-dispatched statement tail: a labeled statement, the missing-label recovery,
     *  or an expression statement.  Extracted verbatim from `parseStatementDispatch`'s
     *  `else` so the `using` / `await using` arms can decline to a head-identical path. */
    private fun parseStatementFallback(): Statement? {
        return run {
            if (isIdentifier() && lookAhead { nextToken(); token == Colon }) {
                parseLabeledStatement()
            } else if (token == Colon) {
                // tsc parseExpressionOrLabeledStatement: the expression parses FIRST — a
                // bare `:` yields a zero-width missing Identifier (TS1109, usually
                // same-start-deduped) which the following colon turns into a LABELED
                // statement with a missing label (`while ( : string, ;` re-parses as
                // `: string, <missing>;` — constructorWithIncompleteTypeAnnotation).
                // List contexts never reach here (parseStatements' Colon arm claims them).
                val mPos = scanner.getTokenPos()
                reportError("Expression expected.", code = 1109)
                nextToken() // consume ':'
                val inner = parseForcedStatement() ?: EmptyStatement()
                LabeledStatement(
                    label = Identifier(text = "", pos = mPos, end = mPos),
                    statement = inner, pos = mPos, end = getEnd(),
                )
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
        // Decorators followed by unparseable garbage (the recovered expression is an
        // EMPTY error-recovery Identifier) — tsc produces a MissingDeclaration that
        // emits NOTHING; discard instead of keeping a stray `;` expression statement.
        if (stmt is ExpressionStatement && (stmt.expression as? Identifier)?.text == "") {
            return null
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

    /** tsc parseBlock's missing-`{` path: report "'{' expected." (same-start-deduped) and
     *  return an EMPTY zero-width missing Block WITHOUT consuming or parsing statements.
     *  Used by try/catch/finally recovery (`catch (Exception) ? }` → `catch (Exception) { }`;
     *  a statement-level `catch`/`finally` gets a missing try block). */
    private fun parseBlockOrMissing(): Block {
        if (token == SyntaxKind.OpenBrace) return parseBlock()
        reportError("'{' expected.", code = 1005)
        val p = scanner.getTokenPos()
        return Block(statements = emptyList(), multiLine = false, pos = p, end = p)
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

    private fun parseVariableDeclarationList(inForInitializer: Boolean = false): VariableDeclarationList {
        val pos = getPos()
        // tsc `parseVariableDeclarationList`: the head token IS the list's flags, except
        // that `await using` is TWO tokens collapsed onto one synthetic flags value
        // (tsc's `NodeFlags.AwaitUsing`); `await` is consumed here and the `using` by the
        // shared `nextToken()` below.  Reaching here with `await` at all means a caller's
        // `isAwaitUsingDeclaration()` lookahead already succeeded.
        val flags = if (token == SyntaxKind.AwaitKeyword) {
            nextToken() // consume `await`; `using` is now current
            SyntaxKind.AwaitUsingKeyword
        } else token
        nextToken() // consume var/let/const/using
        // Capture inline comments between keyword and first declaration (e.g. `var /*c*/ x`)
        val keywordTrailingComments = scanner.getTrailingComments()?.filter { !it.hasPrecedingNewLine }
        val decls = mutableListOf<VariableDeclaration>()
        // tsc parseDelimitedList(VariableDeclarations) skip-recovery BEFORE the first
        // declarator: an Unknown token (the invalid-escape-start backslash of
        // `var 1a;`) gets TS1134 "Variable declaration expected." (same-start-deduped
        // against the scan-time TS1127) and is SKIPPED so the following identifier becomes
        // the declarator (`var u0031a;` — invalidUnicodeEscapeSequance4).
        if (token == SyntaxKind.Unknown && !scanner.hasPrecedingLineBreak() &&
            lookAhead { nextToken(); isIdentifier() }) {
            reportError("Variable declaration expected.", code = 1134, overrideLength = 1)
            nextToken()
        }
        // Only parse declarations if the current token can start one (identifier or binding pattern)
        if (isIdentifier() || token == SyntaxKind.OpenBrace || token == SyntaxKind.OpenBracket) {
            decls.add(parseVariableDeclaration(keywordTrailingComments))
            while (true) {
                if (parseOptional(SyntaxKind.Comma)) {
                    decls.add(parseVariableDeclaration())
                } else if ((isIdentifier() || token == SyntaxKind.OpenBrace || token == SyntaxKind.OpenBracket)
                    && (decls.last().initializer != null ||
                        // A strip-cased invalid-escape-start identifier (`…` after
                        // `var _풥爄쌖`) re-enters the list even with no initializer on the
                        // previous declarator — its ','-expected dedups against the
                        // scan-time TS1127 at the same position (tsc parseDelimitedList).
                        scanner.getTokenPos() == scanner.invalidEscapeIdentStartPos)
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
                } else if (inForInitializer && token == SyntaxKind.CloseParen
                    && !scanner.hasPrecedingLineBreak()) {
                    // tsc abortParsingListOrMoveToNextToken: a stray `)` (not valid in any enclosing
                    // parsing context) after a for-init declarator is `,`-expected + SKIPPED, so the
                    // list continues (`for (let x: y) { z(x); }` → `let x, { z }` + condition `(x)`;
                    // unusedLocalsAndParameters). Errors are pinned; this fixes the JS-emit AST only.
                    parseExpected(SyntaxKind.Comma)
                    nextToken() // skip the stray `)`
                } else if (inForInitializer && (token == SyntaxKind.OpenBracket || token == SyntaxKind.OpenBrace)
                    && !scanner.hasPrecedingLineBreak()) {
                    // tsc parseDelimitedList(VariableDeclarations): a binding-pattern start with
                    // no preceding comma is the NEXT declarator (',' expected); in a for-init the
                    // pattern ABORTS on its first non-binding token (`for (let of [1,2,3])` →
                    // `let of, []` + the C-style condition `1,2,3`; invalidLetInForOfAndForIn_ES5/ES6).
                    // Errors for these tests are pinned — this fixes the JS-emit AST only.
                    parseExpected(SyntaxKind.Comma)
                    bindingPatternAbortMode = true
                    decls.add(parseVariableDeclaration())
                    bindingPatternAbortMode = false
                } else {
                    // tsc parseDelimitedList: a same-line `(` can't start another
                    // declaration and isn't a list terminator (canParseSemicolon/in/of)
                    // — ',' expected before the list aborts (e.g. `var constructor() {}`
                    // recovered at statement level; the statement's follow-up ';'
                    // expected is same-start-deduped).
                    if (token == SyntaxKind.OpenParen && !scanner.hasPrecedingLineBreak()) {
                        reportError("',' expected.", code = 1005, overrideLength = 1)
                    }
                    // tsc abortParsingListOrMoveToNextToken: a same-line `.` or `:` starts
                    // NOTHING in any enclosing context — skip it and RE-ENTER the list
                    // (`const {x} = (a: any).props;` — the missing-'=>' arrow ends
                    // zero-width, the '.' is skipped, `props` becomes a second
                    // declarator; `var y = x:number => x*x` — ',' expected at the ':',
                    // `number` becomes a second declarator and the `=>` TERMINATES the
                    // list per tsc isVariableDeclaratorListTerminator, so the statement's
                    // parseSemicolon reports ';' expected at the '=>').
                    if ((token == SyntaxKind.Dot || token == SyntaxKind.Colon) && !scanner.hasPrecedingLineBreak()) {
                        reportError("',' expected.", code = 1005, overrideLength = 1)
                        nextToken()
                        if (isIdentifier() || token == SyntaxKind.OpenBrace || token == SyntaxKind.OpenBracket) {
                            decls.add(parseVariableDeclaration())
                            continue
                        }
                        // expressionTypeNodeShouldError: a RESERVED keyword after the `.`
                        // (`const x: "".typeof(...)` recovers as declarator-name `typeof`) →
                        // TS1389; the list ABORTS unconsumed so `typeof (...)` re-parses as its
                        // own expression statement (parseSemicolon is silent on `typeof`).
                        if (isKeyword()) {
                            reportError("'${scanner.getTokenValue()}' is not allowed as a variable declaration name.",
                                code = 1389, overrideLength = scanner.getTokenText().length)
                        }
                    }
                    // Same recovery for same-line Unknown junk (`const a =!@#!@$` — the
                    // `#!`-produced Unknown): ',' expected + TS1134 at the junk (both
                    // usually same-start-deduped by the scanner's TS18026), consume it;
                    // an identifier re-enters the list, a non-statement-start token gets
                    // its own TS1134 before the list aborts (tsc emits one per junk token
                    // until isInSomeParsingContext accepts).
                    if (token == SyntaxKind.Unknown && !scanner.hasPrecedingLineBreak()) {
                        reportError("',' expected.", code = 1005, overrideLength = 1)
                        reportError("Variable declaration expected.", code = 1134, overrideLength = 1)
                        nextToken()
                        if (isIdentifier() || token == SyntaxKind.OpenBrace || token == SyntaxKind.OpenBracket) {
                            decls.add(parseVariableDeclaration())
                            continue
                        }
                        if (!canParseSemicolon() && token != SyntaxKind.InKeyword &&
                            !(isIdentifier() && scanner.getTokenValue() == "of")
                        ) {
                            reportError("Variable declaration expected.", code = 1134,
                                overrideLength = scanner.getTokenText().length.coerceAtLeast(1))
                        }
                    }
                    break
                }
            }
        } else if (isKeyword()) {
            // tsc parsingContextErrors(VariableDeclarations): a RESERVED keyword in
            // declarator-name position is TS1389 "'{0}' is not allowed as a variable
            // declaration name." and the list ABORTS unconsumed (`var typeof = 10;` —
            // the keyword re-parses as its own expression statement; the var statement's
            // ';' expected is same-start-deduped). TS1123 is a tsc grammar error
            // (hasParseDiagnostics-suppressed) — never paired with TS1389.
            reportError("'${scanner.getTokenValue()}' is not allowed as a variable declaration name.",
                code = 1389, overrideLength = scanner.getTokenText().length)
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
        // Clear the for-init recovery abort flag (an object element `{ z(x); }` → `{ z }` is already
        // recovered by the loop below; reset so a nested array pattern doesn't wrongly inherit it).
        bindingPatternAbortMode = false
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
        // Read+reset the for-init recovery abort flag so ONLY this outermost pattern aborts
        // (nested patterns parse normally).
        val abortMode = bindingPatternAbortMode
        bindingPatternAbortMode = false
        parseExpected(SyntaxKind.OpenBracket)
        val elements = mutableListOf<Node>()
        var trailingComma = false
        while (token != SyntaxKind.CloseBracket && token != SyntaxKind.EndOfFile) {
            if (token == SyntaxKind.Comma) {
                elements.add(OmittedExpression(pos = getPos(), end = getPos()))
                nextToken()
                continue
            }
            // tsc parseDelimitedList(ArrayBindingElements) abort arm (for-init recovery): a token
            // that can't start a binding element (a numeric/string literal, an operator, …) IS a
            // valid statement/expression start in the enclosing context, so tsc emits TS1181 and
            // leaves it UNCONSUMED (`for (let of [1,2,3])` → `[]` + condition `1,2,3`).
            if (abortMode && !(isIdentifier() || token == SyntaxKind.OpenBracket
                    || token == SyntaxKind.OpenBrace || token == SyntaxKind.DotDotDot)) {
                reportError("Array element destructuring pattern expected.", code = 1181,
                    overrideLength = scanner.getTokenText().length.coerceAtLeast(1))
                break
            }
            // tsc parsingContextErrors(ArrayBindingElements) ABORT arm: a fully-RESERVED
            // keyword (`var [debugger, if] = …`) can't start an element but CAN start a
            // statement in an outer context — TS1181 "Array element destructuring pattern
            // expected." at the keyword and the pattern ABORTS unconsumed (`var [];` +
            // a debugger statement in the reservedWords2 emit; the `]`-expected and the
            // statement's ';'-expected are same-start-deduped).
            if (isKeyword() && !isIdentifier()) {
                reportError("Array element destructuring pattern expected.", code = 1181,
                    overrideLength = scanner.getTokenText().length)
                break
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
        exprStatementStartPos = scanner.getTokenPos()
        exprStatementForced = statementForcedAtDispatch
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
        // The At-token arm mirrors tsc's parseErrorForMissingSemicolonAfter fallthrough:
        // a bare identifier followed on the same line by `@` (e.g. binary garbage `G@...`)
        // is TS1434 at the identifier, not a silent ASI.
        // The identifier/string-follow arm is the parseErrorForMissingSemicolonAfter
        // MAIN path: a bare-identifier statement followed same-line by another
        // identifier/keyword or a string literal (`GOTTA GO`, `HERE's ...`) is TS1434
        // at the identifier. tsc's special-cased identifier texts (var/let/const/
        // declare/interface/is/module/namespace/type) take other diagnostics there —
        // excluded; spelling/space suggestions (TS1435) are not modeled.
        val missingSemiAsUnexpectedIdent = expr is Identifier && !scanner.hasPrecedingLineBreak() &&
            ((token == SyntaxKind.NumericLiteral || token == SyntaxKind.BigIntLiteral) &&
                scanner.getTokenText().startsWith(".") ||
                token == SyntaxKind.At ||
                ((isIdentifier() || isKeyword() || token == SyntaxKind.StringLiteral) &&
                    expr.text !in MISSING_SEMI_SPECIAL_IDENTS &&
                    // `export as namespace X` has no AST node (documented misparse):
                    // the `as` re-parses as a bare-identifier statement followed by
                    // `namespace` — tsc parses the construct properly, so no TS1434.
                    !(expr.text == "as" && scanner.getTokenValue() == "namespace")))
        if (missingSemiAsUnexpectedIdent) {
            // tsc parseErrorForMissingSemicolonAfter: try the keyword spelling/space
            // suggestion first — TS1435 "Unknown keyword or identifier. Did you mean
            // '{0}'?" (`asynd` → 'async', `declareconst` → 'declare const') — and fall
            // back to TS1434 only when no keyword is close (commonMissingSemicolons).
            val identExpr = expr
            val suggestion = getKeywordSpellingSuggestion(identExpr.text)
                ?: getSpaceSuggestion(identExpr.text)
            if (suggestion != null) {
                reportError("Unknown keyword or identifier. Did you mean '$suggestion'?", code = 1435,
                    overrideStart = identExpr.pos, overrideLength = identExpr.text.length.coerceAtLeast(1))
            } else {
                reportError("Unexpected keyword or identifier.", code = 1434,
                    overrideStart = identExpr.pos, overrideLength = identExpr.text.length.coerceAtLeast(1))
            }
        } else if (expr is Identifier && expr.text == "interface" &&
            !scanner.hasPrecedingLineBreak() &&
            token != SyntaxKind.Semicolon && token != SyntaxKind.CloseBrace && token != SyntaxKind.EndOfFile) {
            // tsc parseErrorForMissingSemicolonAfter "interface" special case
            // (parseErrorForInvalidName): `interface { }` → TS1438 "Interface must be
            // given a name." at the `{`; `interface void { }` → TS2427 "Interface name
            // cannot be 'void'." at the offending token. The statement parses as an
            // expression statement (checker adds TS2693 for the value-use of
            // `interface`), and the follow tokens re-parse as their own statement.
            if (token == SyntaxKind.OpenBrace) {
                reportError("Interface must be given a name.", code = 1438,
                    overrideStart = scanner.getTokenPos(), overrideLength = 1)
            } else {
                reportError("Interface name cannot be '${scanner.getTokenValue()}'.", code = 2427,
                    overrideStart = scanner.getTokenPos(),
                    overrideLength = scanner.getTokenText().length.coerceAtLeast(1))
            }
        } else if (expr is Identifier && (expr.text == "namespace" || expr.text == "module") &&
            !scanner.hasPrecedingLineBreak() &&
            token != SyntaxKind.Semicolon && token != SyntaxKind.CloseBrace && token != SyntaxKind.EndOfFile) {
            // tsc parseErrorForMissingSemicolonAfter "namespace"/"module" special case:
            // `namespace void {}` → TS2819 "Namespace name cannot be 'void'." at the
            // token; a bare `{` follow is TS1437 "Namespace must be given a name.".
            if (token == SyntaxKind.OpenBrace) {
                reportError("Namespace must be given a name.", code = 1437,
                    overrideStart = scanner.getTokenPos(), overrideLength = 1)
            } else {
                reportError("Namespace name cannot be '${scanner.getTokenValue()}'.", code = 2819,
                    overrideStart = scanner.getTokenPos(),
                    overrideLength = scanner.getTokenText().length.coerceAtLeast(1))
            }
        } else if (token == SyntaxKind.CloseParen && !scanner.hasPrecedingLineBreak()) {
            // tsc parseErrorForMissingSemicolonAfter (generic fallback): a same-line `)`
            // after an expression statement cannot ASI — ';' expected at the `)`. The
            // statement-level recovery that follows is same-start-deduped against this.
            reportError("';' expected.", code = 1005,
                overrideStart = scanner.getTokenPos(), overrideLength = 1)
        } else if (token == SyntaxKind.OpenBrace && !scanner.hasPrecedingLineBreak() &&
            expr !is Identifier) {
            // B327 (same generic fallback): a same-line `{` after a NON-bare-identifier
            // expression statement — ';' expected at the `{` and the block re-parses as
            // its own statement (`test(name?:any) {}`). Bare identifiers stay silent
            // (the TS1434 paths own those — `interface Foo.I1 { }`'s recovered `I1`).
            reportError("';' expected.", code = 1005,
                overrideStart = scanner.getTokenPos(), overrideLength = 1)
        } else if (token == SyntaxKind.OpenBrace && !scanner.hasPrecedingLineBreak() &&
            expr is Identifier && expr.text !in MISSING_SEMI_SPECIAL_IDENTS) {
            // A bare identifier followed by a same-line `{` reaches tsc's
            // parseErrorForMissingSemicolonAfter too: TS1435 with a keyword suggestion
            // (`interfaceMyInterface { }` → 'interface MyInterface'), else TS1434 at
            // the identifier (`clasd MyClass2 {}`'s MyClass2). The recovered-identifier
            // shapes (interfaceDeclaration4's I1) already carry a TS1434 at the same
            // start from their own recovery — the global same-start dedup absorbs this
            // one.
            val suggestion = getKeywordSpellingSuggestion(expr.text) ?: getSpaceSuggestion(expr.text)
            if (suggestion != null) {
                reportError("Unknown keyword or identifier. Did you mean '$suggestion'?", code = 1435,
                    overrideStart = expr.pos, overrideLength = expr.text.length.coerceAtLeast(1))
            } else {
                reportError("Unexpected keyword or identifier.", code = 1434,
                    overrideStart = expr.pos, overrideLength = expr.text.length.coerceAtLeast(1))
            }
        } else if (token == SyntaxKind.Semicolon) {
            nextToken()
        } else if (!canParseSemicolon()) {
            // tsc parseErrorForMissingSemicolonAfter: a NON-identifier expression falls to
            // the generic "';' expected." at the current token; a bare-Identifier expression
            // takes the did-you-mean family (the arms above — the special idents var/let/
            // const/declare/interface/module/namespace/type are deliberately silent here).
            // The narrow `:`/`?`/`=>`/numeric/bigint triggers stay active for Identifier
            // exprs (pre-existing behavior).
            if (expr !is Identifier ||
                token == SyntaxKind.Colon || token == SyntaxKind.Question ||
                token == SyntaxKind.EqualsGreaterThan ||
                token == SyntaxKind.NumericLiteral || token == SyntaxKind.BigIntLiteral) {
                reportError("';' expected.", code = 1005,
                    overrideStart = scanner.getTokenPos(),
                    overrideLength = scanner.getTokenText().length.coerceAtLeast(1))
            }
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
        val openParenOk = parseExpected(SyntaxKind.OpenParen)
        val afterOpenParen = trailingComments()
        val expr = parseExpression()
        val beforeCloseParen = trailingComments()
        // TS1007 related info only when the '(' was actually consumed (see while).
        parseExpectedClosing(SyntaxKind.CloseParen, if (openParenOk) openParenPos else -1)
        val afterCloseParen = trailingComments()
        val thenStmt = parseForcedStatement() ?: EmptyStatement()
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
        val elseStmt = if (hasElse) parseForcedStatement() else null
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
        val stmt = parseForcedStatement() ?: EmptyStatement()
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
        val openParenOk = parseExpected(SyntaxKind.OpenParen)
        val afterOpenParen = trailingComments()
        val expr = parseExpression()
        val beforeCloseParen = trailingComments()
        // The TS1007 "to match the '(' token here" related info only makes sense when
        // the '(' was actually CONSUMED — a while-statement recovered from `while = …`
        // (reservedWords2) has no opening paren to point at.
        parseExpectedClosing(SyntaxKind.CloseParen, if (openParenOk) openParenPos else -1)
        val afterCloseParen = trailingComments()
        val stmt = parseForcedStatement() ?: EmptyStatement()
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

    private fun parseForInitializerDeclList(): VariableDeclarationList {
        disallowIn = true
        val v = parseVariableDeclarationList(inForInitializer = true)
        disallowIn = false
        return v
    }

    private fun parseForInitializerExpression(): Expression {
        disallowIn = true
        val e = parseExpression()
        disallowIn = false
        return e
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
            VarKeyword, LetKeyword, ConstKeyword -> parseForInitializerDeclList()
            // `for (using x of xs)` / `for (await using x of xs)`.  `disallowOf` is what
            // keeps `for (using of xs)` an iteration over the VALUE `using` (tsc
            // `nextTokenIsBindingIdentifierOrStartOfDestructuringOnSameLineDisallowOf`).
            UsingKeyword -> if (isUsingDeclaration(disallowOf = true)) {
                parseForInitializerDeclList()
            } else parseForInitializerExpression()
            AwaitKeyword -> if (isAwaitUsingDeclaration()) {
                parseForInitializerDeclList()
            } else parseForInitializerExpression()
            Semicolon -> null
            CloseParen -> {
                // `for ()` — missing init AND semicolons. Report TS1109 "Expression expected"
                // at the `)` position (matching TypeScript's behavior).
                reportError("Expression expected.", code = 1109)
                forMissingHeader = true
                null
            }
            else -> parseForInitializerExpression()
        }

        // Capture trailing comments on the initializer (between init and in/of/;)
        // For VariableDeclarationList, the trailing comments are already captured
        // on the last variable declaration's nameTrailingComments, so skip here.
        val afterInit = if (initializer !is VariableDeclarationList) trailingComments() else null

        // `for ()` error recovery: skip straight to CloseParen parsing (no semicolons needed)
        if (forMissingHeader) {
            parseExpected(SyntaxKind.CloseParen)
            val afterCloseParen = trailingComments()
            val body = parseForcedStatement() ?: EmptyStatement()
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
            val body = parseForcedStatement() ?: EmptyStatement()
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
            val body = parseForcedStatement() ?: EmptyStatement()
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
        val body = parseForcedStatement() ?: EmptyStatement()
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
        val stmt = parseForcedStatement() ?: EmptyStatement()
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
            // TS1260: an escaped keyword form (e.g. `case`) decoded to `case`.
            // Emitted before consuming so the scanner span covers the keyword token.
            if (scanner.hasTokenUnicodeEscape()) {
                reportError("Keywords cannot contain escape characters.", 1260)
            }
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
            // TS1260: an escaped keyword form (e.g. `default`) decoded to `default`.
            if (scanner.hasTokenUnicodeEscape()) {
                reportError("Keywords cannot contain escape characters.", 1260)
            }
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
        val tryBlock = parseBlockOrMissing()
        val afterTryBlock = scanner.consumeTrailingComments()
        val catchClause = if (token == SyntaxKind.CatchKeyword) parseCatchClause() else null
        val afterCatchBlock = if (catchClause != null) trailingComments() else null
        // tsc: if we don't have a catch clause, we MUST have a finally clause — try to
        // parse one no matter what (TS1472 "'catch' or 'finally' expected." reported at
        // the offending token, usually same-start-deduped in cascades) and give the try a
        // MISSING finally block. A catch followed by a real `finally` parses it normally.
        val needsFinally = catchClause == null || token == SyntaxKind.FinallyKeyword
        val hasFinally = if (needsFinally) {
            if (token == SyntaxKind.FinallyKeyword) { nextToken(); true }
            else { reportError("'catch' or 'finally' expected.", code = 1472); true }
        } else false
        val afterFinally = if (hasFinally) trailingComments() else null
        val finallyBlock = if (hasFinally) parseBlockOrMissing() else null
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
            // (GATE.2) A span, so a caret on `err` in `catch (err)` descends to it.
            // Built here rather than defaulted to [0, 0): a node with an empty span
            // is one `SourceIndex.pathAt` can never enter, so every position query
            // answered about the catch clause instead of the variable.
            VariableDeclaration(
                name = name, type = type, initializer = initializer,
                pos = name.pos, end = initializer?.end ?: type?.end ?: name.end,
            )
        } else null
        val block = parseBlockOrMissing()
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
        // A same-line ',' after `debugger` can't ASI — "';' expected." at the comma
        // (reservedWords2's recovered `debugger, if] = …`; the statement-level TS1128
        // at the same comma is then same-start-deduped). Kept OUT of the general
        // parseSemicolon trigger set: type-literal member separators route through
        // parseSemicolon too (strictSubtypeAndNarrowing caught the broad version).
        if (token == SyntaxKind.Comma && !scanner.hasPrecedingLineBreak()) {
            reportError("';' expected.", code = 1005, overrideLength = 1)
        }
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
        val stmt = parseForcedStatement() ?: EmptyStatement()
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
            // B324: tsc parseBindingIdentifier errors for ')' follows too
            // (`function ) {}` statement-level recovery), and synthesizes a ZERO-WIDTH
            // missing-name Identifier (createMissingNode) — the binder binds the empty
            // name (empty-name TS2567 merges) and TS7010 displays '(Missing)'.
            if (ModifierFlag.Default !in modifiers &&
                (token == SyntaxKind.OpenParen || token == SyntaxKind.CloseParen)) {
                reportError("Identifier expected.", code = 1003,
                    overrideStart = scanner.getTokenPos(), overrideLength = 1)
                Identifier(text = "", pos = scanner.getTokenPos(), end = scanner.getTokenPos())
            } else if (ModifierFlag.Default !in modifiers && isKeyword() &&
                !scanner.hasPrecedingLineBreak()) {
                // tsc createIdentifier: a RESERVED word in function-name position reports
                // TS1359 at the token WITHOUT consuming it (`function function() {}` /
                // `function throw() {}` — the '(' -expected from the empty param-list
                // parse is same-start-deduped, and the reserved word re-parses as its
                // own statement). The missing name is ZERO-WIDTH at the prev token's end
                // (reservedWords2 anchors TS7010/TS2300 at (5,9), the space after
                // `function`).
                parseDeclarationNameOrMissing()
            } else null
        }
        val parsedTypeParams = parseTypeParametersOpt()
        // 17.147 / B5.4: in JS-like files, fall back to JSDoc `@template T`
        // when no TS-level `<T>` was parsed. Mirror of B5.3 for ClassDeclaration.
        val typeParams = parsedTypeParams ?: parseJSDocTemplateTypeParams(comments)
        val rawParams = parseParameterList()
        val sigAborted = paramListAborted
        // 17.140: in JS-like files, bridge JSDoc `@param {primitive} name` tags
        // to parameter types when the parameter is un-annotated.
        val params = applyJSDocParamPrimitiveTypes(rawParams, comments)
        val returnType = if (parseOptional(SyntaxKind.Colon)) parseType() else null
        val savedAsync = inAsyncContext
        inAsyncContext = ModifierFlag.Async in modifiers
        functionLikeDepth++
        val body = if (token == SyntaxKind.OpenBrace) parseBlock()
            else if (!canParseSemicolon()) {
                // B324/B325 (tsc parseFunctionBlockOrSemicolon with Diagnostics.or_expected):
                // the same-line follow token isn't '{' and ASI can't apply — TS1144
                // "'{' or ';' expected." (same-start-deduped after an aborted signature)
                // and the body is a MISSING zero-width Block (pos == end, no closeBrace).
                // The checker's TS7010/TS2391 bodyless rules treat it as bodyless; the
                // emitter prints `{ }`. EXCEPTION (empirical, dottedModuleName): a '=>'
                // follow keeps a NULL body — tsc ERASES `function f(x)=>…;` from the
                // emit, while other recovered shapes print `function f() { }`; the
                // parseSemicolon below same-start-dedups its TS1005 against the TS1144.
                reportError("'{' or ';' expected.", code = 1144)
                if (token == SyntaxKind.EqualsGreaterThan) null
                else Block(statements = emptyList(), multiLine = false,
                    pos = scanner.getTokenPos(), end = scanner.getTokenPos(), closeBracePos = -1)
            } else null
        functionLikeDepth--
        inAsyncContext = savedAsync
        if (body == null) parseSemicolon()
        val trailing = trailingComments()
        return FunctionDeclaration(
            name = name, typeParameters = typeParams, parameters = params,
            type = returnType, body = body, modifiers = modifiers, asteriskToken = asterisk,
            pos = pos, end = getEnd(), leadingComments = comments, trailingComments = trailing,
            signatureAborted = sigAborted,
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
        // `implements`/`extends` start heritage clauses ONLY when followed by an
        // identifier-or-keyword (tsc isImplementsClause); `class implements { }` takes
        // `implements` as the class NAME (the checker reports TS1213 on it).
        val heritageKeywordIsName = (token == SyntaxKind.ImplementsKeyword || token == SyntaxKind.ExtendsKeyword) &&
            !lookAhead { nextToken(); isIdentifier() || isKeyword() }
        val name = if (isIdentifier() && (heritageKeywordIsName ||
                (token != SyntaxKind.ImplementsKeyword && token != SyntaxKind.ExtendsKeyword))
        ) parseIdentifier() else null
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
        // B324 (tsc parseList HeritageClauses skip-recovery): after heritage clauses, an
        // Unknown token (invalid character) that can't start another clause or any outer
        // context is SKIPPED with TS1127 (mirrors parseExpected's Unknown branch — tsc's
        // context error dedups against the scan-time invalid-character report), letting
        // the '{' body parse succeed.
        if (heritage != null) {
            while (token == SyntaxKind.Unknown) {
                reportError("Invalid character.", code = 1127)
                nextToken()
            }
        }
        val beforeOpenBrace = scanner.consumeTrailingComments()
        if (!parseExpected(SyntaxKind.OpenBrace)) {
            // B324 (tsc parseClassDeclarationOrExpression): a missing '{' yields a MISSING
            // member list — nothing further is consumed; the offending token stays for the
            // outer context (`class ) {}` leaves ')' and the block for statement recovery).
            val trailingM = trailingComments()
            return ClassDeclaration(
                name = name, typeParameters = typeParams, heritageClauses = heritage,
                members = emptyList(), modifiers = modifiers, decorators = decorators,
                beforeOpenBraceComments = beforeOpenBrace, pos = pos, end = getEnd(),
                leadingComments = comments, trailingComments = trailingM,
            )
        }
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
        // Suppress value-position instantiation-expr conversion for the base SPINE so a
        // non-last generic base (`extends ReadonlyArray<T>, TextRange`) keeps its type
        // arguments (they are re-parsed by parseTypeArgumentsOpt below). See parsingHeritageBase.
        val savedHeritage = parsingHeritageBase
        parsingHeritageBase = true
        val expr = try { parseLeftHandSideExpression() } finally { parsingHeritageBase = savedHeritage }
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
                // B325 (tsc parseList ClassMembers skip-recovery): a ',' can't start a
                // member and can't start/terminate any outer context — TS1068 (same-start
                // deduped against a preceding TS1144 at the same comma) + SKIP + continue
                // the member list (`m1(), m1();` keeps parsing m1's second declaration).
                if (token == SyntaxKind.Comma) {
                    reportError("Unexpected token. A constructor, method, accessor, or property was expected.", code = 1068)
                    nextToken()
                    continue
                }
                // B331 (same tsc recovery, ABORT arm): a bare `{` can't start a member
                // but CAN start a statement in the outer SourceElements context — TS1068
                // at the `{` (same-start-deduped against a preceding ';'-expected there)
                // and the member list ABORTS unconsumed: the class's '}'-expected is
                // deduped too, `{}` re-parses as a statement-level block, and the class's
                // own `}` becomes the orphan TS1128 (commonMissingSemicolons classes A-C).
                // `static { … }` blocks are unaffected — their `{` follows the consumed
                // modifier inside parseClassMember.
                if (token == SyntaxKind.OpenBrace) {
                    reportError("Unexpected token. A constructor, method, accessor, or property was expected.", code = 1068, overrideLength = 1)
                    break
                }
                // tsc abortParsingListOrMoveToNextToken(ClassMembers): a token that cannot
                // start a member — TS1068 (same-start-deduped), then ABORT unconsumed when an
                // enclosing context claims it (a statement start: the `!= 0;` left after an
                // aborted `if (retValue` method signature re-parses at the namespace level),
                // else SKIP it and continue the member list (`case  d = …` → TS1068 at `case`,
                // then `d = …` parses as a property — constructorWithIncompleteTypeAnnotation).
                if (!isClassMemberStartLookahead()) {
                    reportError("Unexpected token. A constructor, method, accessor, or property was expected.",
                        code = 1068, overrideLength = scanner.getTokenText().length.coerceAtLeast(1))
                    if (canStartStatementForRecovery()) break
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
            // tsc parseErrorForMissingSemicolonAfter: fires only when the member NAMED
            // 'static' is followed by another same-line identifier/keyword (the
            // missing-semicolon shape, `static static foo`). A follow token like
            // `=`/`:`/`(` makes `static` a legal member name (`public static = 0;`).
            val nextIsIdentSameLine = lookAhead {
                nextToken() // skip the second 'static'
                (isIdentifier() || isKeyword()) && !scanner.hasPrecedingLineBreak()
            }
            if (nextIsIdentSameLine) {
                reportError("Unexpected keyword or identifier.", code = 1434, overrideLength = "static".length)
            }
        }

        // tsc isClassMemberStart: `global` followed by a same-line identifier is NOT a
        // member start — the class-members list aborts (TS1068 at `global`; the class's
        // following '}'-expected is same-start-deduped) and `global <ident>` re-parses
        // at the statement level as a global-augmentation declaration.
        if (token == SyntaxKind.GlobalKeyword) {
            val nextIsIdentSameLine = lookAhead {
                nextToken()
                token == SyntaxKind.Identifier && !scanner.hasPrecedingLineBreak()
            }
            if (nextIsIdentSameLine) {
                reportError("Unexpected token. A constructor, method, accessor, or property was expected.",
                    code = 1068, overrideLength = "global".length)
                return null
            }
        }

        // tsc isClassMemberStart: a RESERVED keyword in member-name position is a
        // member start only when followed by (, <, !, :, =, ?, or a semicolon-ish
        // token (canParseSemicolon: ';', '}', EOF, preceding line break). Otherwise
        // the class-members list aborts — TS1068 at the keyword, the class's
        // follow-up '}'-expected is same-start-deduped — and the line re-parses at
        // statement level (e.g. `var constructor() { }` inside a class body).
        // Gated to no-decorators/no-modifiers: with consumed modifiers tsc's
        // lookahead returns true early at any class-member modifier.
        if (decorators.isNullOrEmpty() && modifiers.isEmpty() && isKeyword() && !isIdentifier() &&
            token != SyntaxKind.StaticKeyword
        ) {
            val kwLen = scanner.getTokenText().length
            val isMemberStart = lookAhead {
                nextToken()
                token == SyntaxKind.OpenParen || token == SyntaxKind.LessThan ||
                        token == SyntaxKind.Exclamation || token == SyntaxKind.Colon ||
                        token == SyntaxKind.Equals || token == SyntaxKind.Question ||
                        canParseSemicolon()
            }
            if (!isMemberStart) {
                reportError("Unexpected token. A constructor, method, accessor, or property was expected.",
                    code = 1068, overrideLength = kwLen)
                return null
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
                // (GATE.2) A span — see parseCatchClause.
                val param = Parameter(
                    name = paramName, type = paramType,
                    pos = paramName.pos, end = paramType?.end ?: paramName.end,
                )
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
            functionLikeDepth++
            // B325 (tsc parseFunctionBlockOrSemicolon with Diagnostics.or_expected): a
            // same-line non-'{' follow (no ASI) gets TS1144 "'{' or ';' expected." and a
            // MISSING zero-width Block body — the checker treats it as bodyless and the
            // emitter prints `{ }` (`m1(), m1();` keeps m1's first declaration emitted).
            val body = if (token == SyntaxKind.OpenBrace) parseBlock()
                else if (!canParseSemicolon() && ModifierFlag.Abstract !in modifiers) {
                    reportError("'{' or ';' expected.", code = 1144)
                    Block(statements = emptyList(), multiLine = false,
                        pos = scanner.getTokenPos(), end = scanner.getTokenPos(), closeBracePos = -1)
                } else {
                    parseSemicolon(); null
                }
            functionLikeDepth--
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
            // tsc parseSemicolonAfterPropertyName → parseErrorForMissingSemicolonAfter:
            // a property NAMED by the keyword var/let/const with no type, no initializer
            // and no parseable semicolon reports TS1440 at the NAME span instead of
            // "';' expected." (e.g. `public const var export foo = 10;` — the member is
            // the `var`-named property; `export foo = 10` re-parses as the next member).
            if (type == null && init == null && name is Identifier &&
                (name.text == "var" || name.text == "let" || name.text == "const") &&
                !canParseSemicolon()
            ) {
                // Direct add bypasses reportError's same-start dedup — the 17.167
                // TS1248 may already sit at the same name position (the driver's
                // B310 grammar filter strips it when real parse errors exist,
                // exactly this scenario).
                val (lineN, charN) = getLineAndCharacterOfPosition(name.pos)
                diagnostics.add(Diagnostic(
                    message = "Variable declaration not allowed at this location.",
                    category = DiagnosticCategory.Error,
                    code = 1440,
                    fileName = fileName,
                    line = lineN,
                    character = charN,
                    start = name.pos,
                    length = name.text.length,
                ))
            } else if (token == SyntaxKind.OpenBrace && !scanner.hasPrecedingLineBreak()) {
                // tsc parseSemicolonAfterPropertyName generic fallback: a same-line `{`
                // after a property can't ASI — "';' expected." at the `{`; the member
                // list then aborts on it (TS1068 same-start-deduped) and the block
                // re-parses at statement level (commonMissingSemicolons classes D/E:
                // `['a'] = 0` continues as `0['b']()` and the method's `{` is reached
                // here as the property's follow token).
                reportError("';' expected.", code = 1005,
                    overrideStart = scanner.getTokenPos(), overrideLength = 1)
            } else {
                parseSemicolon()
            }
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
        // A constructor cannot have a return-type annotation, but tsc PARSES `: <type>`
        // (the grammar checker owns the error) — `constructor(…)  :  }` consumes the `:`
        // and the missing type reports TS1110 "Type expected." at the `}` (unconsumed),
        // after which ASI gives a bodyless ctor signature (constructorWithIncompleteTypeAnnotation).
        if (token == SyntaxKind.Colon) {
            nextToken()
            if (isStartOfType(token)) parseType()
            else reportError("Type expected.", code = 1110,
                overrideStart = scanner.getTokenPos(),
                overrideLength = scanner.getTokenText().length.coerceAtLeast(1))
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
        // tsc parseFunctionBlockOrSemicolon: a bodyless accessor is ACCEPTED silently when
        // canParseSemicolon() (ASI / `;` / `}` / EOF) — the '{' expected diagnostic is the
        // CHECKER's (checkGrammarAccessor grammarErrorAtPos at accessor.end - 1, ambient-gated).
        // Only a same-line non-`;` follower is a real parse error (tsc parseFunctionBlock →
        // parseExpected(OpenBrace) at the current token).
        var bodylessEnd = -1
        val body = if (token == SyntaxKind.OpenBrace) parseBlock() else {
            if (canParseSemicolon()) {
                parseSemicolon()
                bodylessEnd = scanner.getPrevTokenEnd()
            } else {
                // Same-line garbage after the signature — a real parse error (tsc parseFunctionBlock).
                reportErrorAtPrevTokenEnd("'{' expected.")
                parseSemicolon()
            }
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
            bodylessEnd = bodylessEnd,
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
                val nm = p.name
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
        // Same tsc parseFunctionBlockOrSemicolon split as parseGetAccessor: bodyless + ASI-able
        // is accepted silently (checker owns the TS1005 at bodylessEnd - 1); the synthetic
        // Block(pos=-1,end=-1) keeps the Transformer's `set x(v) { }` emit.
        var bodylessEnd = -1
        val body = if (token == SyntaxKind.OpenBrace) parseBlock() else {
            if (canParseSemicolon()) {
                parseSemicolon()
                bodylessEnd = scanner.getPrevTokenEnd()
            } else {
                reportErrorAtPrevTokenEnd("'{' expected.")
                parseSemicolon()
            }
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
            bodylessEnd = bodylessEnd,
        )
    }

    // Explicit return type: the branches' LUB degraded once the node classes gained the
    // common NodeBase superclass (INV.2(a)) — inference would approximate to Any.
    private fun parsePropertyName(): NameNode = when (token) {
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
            // tsc parseList(TypeMembers) abort: a LEADING `?` / `:` cannot start a type
            // member (isTypeMemberStart returns false) — `?(): any`, `? [idx]: any`, and
            // the `:` left dangling after a `()?` recovery. Mirror tsc's
            // abortParsingListOrMoveToNextToken: emit TS1131 "Property or signature
            // expected." at the token (same-start-deduped against a preceding TS1005 from
            // parseSemicolon — `()?: any` reports TS1005 at the `?`, the abort's TS1131 at
            // the `?` is suppressed, then TS1131 fires at the `:`) and skip one token.
            if (token == SyntaxKind.Question || token == SyntaxKind.Colon) {
                reportError(
                    "Property or signature expected.", code = 1131,
                    overrideStart = getPos(),
                    overrideLength = (getEnd() - getPos()).coerceAtLeast(1),
                )
                nextToken()
                continue
            }
            val member = parseTypeMember()
            if (member != null) members.add(member)
            // parseTypeMember can signal a member-list ABORT (malformed-type-param
            // recovery): the offending token belongs to an outer parsing context —
            // stop without consuming it; the caller skips the CloseBrace consume.
            if (interfaceMembersBailedOnKeyword) break
            // tsc parseTypeMemberSemicolon: a comma OR a (possibly-ASI) semicolon. Using
            // parseSemicolon (not a silent parseOptional) makes a same-line non-`;`
            // follower — e.g. the `?` after a `()` call signature or `[idx:number]` index
            // signature — report TS1005 "';' expected." (matching tsc), which then leaves
            // the `?` for the leading-`?` abort above.
            if (!parseOptional(SyntaxKind.Comma)) parseSemicolon()
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
                    // tsc aborts the type-param list AND the enclosing member list here:
                    // the offending token is owned by an outer parsing context (e.g. `-`
                    // starts an expression statement), so it stays UNCONSUMED and
                    // re-parses at the statement level — `var f: { x: number; <- };`
                    // yields tsc's `-;` + `;` emit with TS1109 at the missing unary
                    // operand. The '}'-expected / ';'-expected attempts at the same
                    // position are same-start-deduped, mirroring tsc.
                    interfaceMembersBailedOnKeyword = true
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

        // `new` starts a CONSTRUCT SIGNATURE only when followed by `(` or `<`
        // (tsc isStartOfConstructSignature lookahead); otherwise it's a PROPERTY
        // named `new` (`interface I { new; }` — no error).
        if (token == SyntaxKind.NewKeyword &&
            lookAhead { nextToken(); token == SyntaxKind.OpenParen || token == SyntaxKind.LessThan }
        ) {
            // (GATE.2) The name is synthesized, but the text it names is REAL — the
            // `new` keyword — so it carries that keyword's own span rather than the
            // default `[0, 0)`. A zero-span node at offset 0 is one no descent can
            // enter and one a whole-file identifier sweep reports as an empty entry
            // at the top of the file. `getPos()` here rather than the member's
            // `pos`, which for `abstract new (): T` is the modifier's.
            val newPos = getPos()
            nextToken()
            val newName = Identifier("new", pos = newPos, end = getEnd())
            val typeParams = parseTypeParametersOpt()
            val params = parseParameterList()
            val type = if (parseOptional(SyntaxKind.Colon)) parseType() else null
            return MethodDeclaration(
                name = newName,
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
            // (GATE.2) A span — see parseCatchClause.
            val param = Parameter(
                name = paramName, type = paramType,
                pos = paramName.pos, end = paramType?.end ?: paramName.end,
            )
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
            // (GATE.2) A span — see parseCatchClause.
            params.add(
                Parameter(
                    name = paramName, type = paramType,
                    pos = paramName.pos, end = paramType.end,
                ),
            )
            // Parse any additional parameters (invalid — TS1096 for multi-param index signature)
            var hasExtraParams = false
            while (token == SyntaxKind.Comma) {
                val commaPos = getPos()
                nextToken() // consume ,
                if (token == SyntaxKind.CloseBracket || token == SyntaxKind.EndOfFile) {
                    // TS1025: An index signature cannot have a trailing comma.
                    reportError("An index signature cannot have a trailing comma.", code = 1025,
                        overrideStart = commaPos, overrideLength = 1)
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
            // Only consume a trailing `;` (extends span to include it for TS1021); a
            // non-`;` same-line follower (e.g. the `?` of `[idx:number]?`) is reported by
            // the member loop's parseSemicolon — emitting TS1005 here too would double it.
            parseOptional(SyntaxKind.Semicolon)
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
        // If there's a line break between 'type' and the name, ASI applies — not a type alias.
        // Inside scanner.lookAhead the parser's `token` field is NOT updated, so check the
        // SCANNED token (`isIdentifierToken(scanner.getToken())`) — `isIdentifier()` would check
        // the still-cached `type` keyword and wrongly accept `type 100 {}` (parseInvalidNames).
        !scanner.hasPrecedingLineBreak() && isIdentifierToken(scanner.getToken()) &&
            scanner.getToken() != SyntaxKind.Dot
    }

    private fun parseTypeAliasDeclaration(
        modifiers: Set<ModifierFlag> = emptySet(),
        outerComments: List<Comment>? = null,
    ): TypeAliasDeclaration {
        val pos = getPos()
        val comments = outerComments ?: leadingComments()
        parseExpected(SyntaxKind.TypeKeyword)
        // TS1142 (tsc parseTypeAliasDeclaration): a line break between the `type`
        // keyword and the alias NAME is not permitted. tsc reports at the current
        // token (the name) via parseErrorAtCurrentToken — span = the name token.
        if (scanner.hasPrecedingLineBreak()) {
            val nameStart = scanner.getTokenPos()
            val nameEnd = scanner.getPos()
            reportError(
                "Line break not permitted here.",
                code = 1142,
                overrideStart = nameStart,
                overrideLength = (nameEnd - nameStart).coerceAtLeast(0),
            )
        }
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
        val missingTypeBody = token != SyntaxKind.Equals && token != SyntaxKind.Bar &&
            token != SyntaxKind.Ampersand && !isStartOfType(token)
        val offendingTokenPos = scanner.getTokenPos()
        val afterNamePos = scanner.getPrevTokenEnd()
        val eqConsumed = parseExpected(SyntaxKind.Equals)
        if (missingTypeBody && !eqConsumed && afterNamePos != offendingTokenPos) {
            // Case (a): missing `=`. tsc reports TS1005 '=' expected at the offending
            // token (above) and TS1110 "Type expected." at the position right after the
            // name — SUPPRESSED when both land on the same position (`type type;` keeps
            // only the TS1005; `export type test\n…` keeps both — tsc same-start dedup).
            reportError(
                message = "Type expected.",
                code = 1110,
                overrideStart = afterNamePos,
                overrideLength = 0,
            )
        }
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
        // expressionTypeNodeShouldError: `type X = "".typeof(...)` leaves a trailing `.` after the
        // literal type (parsePrimaryType doesn't consume `.member`) → TS1005 ';' expected at the
        // `.`, then CONSUME it so the trailing `typeof(...)` re-parses cleanly as the next statement.
        if (token == SyntaxKind.Dot && !scanner.hasPrecedingLineBreak()) {
            reportError("';' expected.", code = 1005, overrideLength = 1)
            nextToken()
        }
        parseSemicolon()
        return TypeAliasDeclaration(
            name = name,
            typeParameters = typeParams,
            type = type,
            modifiers = modifiers,
            pos = pos,
            end = getEnd(),
            leadingComments = comments
        ).also { decl ->
            if (typeParams?.any { it.default != null } == true) tpDefaultAliases.add(decl)
        }
    }

    /** tsc isNumericLiteralName: a string is a numeric name iff `String(Number(s)) === s`
     *  ("3"/"1.5"/"-1" canonical; "03"/"1e3"/"13e-1"/"foo" are NOT). Used for TS2452 on
     *  string / computed-string enum member names (B451). */
    private fun isEnumNumericName(s: String): Boolean {
        if (s.isEmpty()) return false
        val d = s.toDoubleOrNull() ?: return false
        val canonical = if (d == d.toLong().toDouble() && 'e' !in s && 'E' !in s) {
            d.toLong().toString()
        } else {
            d.toString()
        }
        return canonical == s
    }

    private fun parseEnumDeclaration(
        modifiers: Set<ModifierFlag> = emptySet(),
        outerComments: List<Comment>? = null,
    ): EnumDeclaration {
        val pos = getPos()
        val comments = outerComments ?: leadingComments()
        parseExpected(SyntaxKind.EnumKeyword)
        // `enum void {}` — a RESERVED name is TS1359 + a zero-width missing name; the
        // '{'-expected at `void` is deduped, the member list goes missing (B324 arm
        // below) and `void {}` re-parses as a void-expression statement (reservedWords2).
        val name = parseDeclarationNameOrMissing()
        if (!parseExpected(SyntaxKind.OpenBrace)) {
            // B324 (tsc parseEnumDeclaration): a missing '{' yields a MISSING member list —
            // nothing further is consumed; the offending token stays for the outer context.
            val trailingM = trailingComments()
            return EnumDeclaration(
                name = name, members = emptyList(), modifiers = modifiers,
                pos = pos, end = getEnd(), leadingComments = comments, trailingComments = trailingM,
            )
        }
        val members = mutableListOf<EnumMember>()
        while (token != SyntaxKind.CloseBrace && token != SyntaxKind.EndOfFile) {
            val mPos = getPos()
            val mLeading = leadingComments()
            val mName = parsePropertyName()
            // True source end of the name node (incl. closing bracket/quote) — avoids the
            // node.end overshoot for ComputedPropertyName/StringLiteralNode spans (B451).
            val mNameEnd = scanner.getPrevTokenEnd()
            // 17.183: TS2452 — enum member cannot have a numeric (or bigint) name.
            // `parsePropertyName` returns NumericLiteralNode for `0`/`1.5`, BigIntLiteralNode
            // for `0n`, StringLiteralNode for `"3"`, and ComputedPropertyName for `[2]`/`["4"]`.
            // tsc fires for a numeric/bigint literal name, a numeric-CANONICAL string name
            // (`"3"` yes, `"13e-1"` no — isNumericLiteralName), or a computed name whose inner
            // expression is one of those (B451 — `literalsInComputedProperties1`). Span covers
            // the whole name node (literal text for numeric/bigint; getPrevTokenEnd for the
            // string/computed forms so quotes/brackets are included).
            when (mName) {
                is NumericLiteralNode -> reportError(
                    "An enum member cannot have a numeric name.",
                    code = 2452, overrideStart = mName.pos, overrideLength = mName.text.length,
                )
                is BigIntLiteralNode -> reportError(
                    "An enum member cannot have a numeric name.",
                    code = 2452, overrideStart = mName.pos, overrideLength = mName.text.length,
                )
                is StringLiteralNode -> if (isEnumNumericName(mName.text)) reportError(
                    "An enum member cannot have a numeric name.",
                    code = 2452, overrideStart = mName.pos,
                    overrideLength = (mNameEnd - mName.pos).coerceAtLeast(1),
                )
                is ComputedPropertyName -> {
                    val numeric = when (val inner = mName.expression) {
                        is NumericLiteralNode, is BigIntLiteralNode -> true
                        is StringLiteralNode -> isEnumNumericName(inner.text)
                        else -> false
                    }
                    if (numeric) reportError(
                        "An enum member cannot have a numeric name.",
                        code = 2452, overrideStart = mName.pos,
                        overrideLength = (mNameEnd - mName.pos).coerceAtLeast(1),
                    )
                }
                else -> { /* Identifier ok */ }
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
            // `declare global { ... }` — no module name, just the body.
            // (GATE.2) `getEnd()`, not `pos + 6`: every other node in this parser
            // carries the END OF THE FOLLOWING TOKEN, and a reader that snaps that
            // back to the token stream (`SourceIndex.realEndOf`) turns an already-
            // exact end into the end of the token BEFORE it — an empty span, which
            // no descent can enter. The name is the one node here that broke the
            // convention, so a caret on `global` answered about the module.
            Identifier(text = "global", pos = pos, end = getEnd())
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
        } else {
            // tsc parseAmbientExternalModuleDeclaration: a body-less `global` declaration
            // takes a semicolon — `global x` reports TS1005 ';' expected at the same-line
            // follow token (which then re-parses as its own statement).
            if (isGlobal) {
                if (token == SyntaxKind.Semicolon) nextToken()
                else if (!scanner.hasPrecedingLineBreak() && token != SyntaxKind.CloseBrace &&
                    token != SyntaxKind.EndOfFile) {
                    reportError("';' expected.", code = 1005,
                        overrideStart = scanner.getTokenPos(),
                        overrideLength = scanner.getTokenText().length.coerceAtLeast(1))
                }
            }
            null
        }
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
        ieSlots = mutableListOf()
        parseExpected(SyntaxKind.ImportKeyword)

        // import type ...
        val isTypeOnly = token == SyntaxKind.TypeKeyword && scanner.lookAhead {
            scanner.scan()
            isIdentifier() || scanner.getToken() == SyntaxKind.OpenBrace || scanner.getToken() == SyntaxKind.Asterisk
        }
        if (isTypeOnly) nextToken()
        captureIeSlot() // after `import` (and `type`), before the clause/specifier

        // tsc parseImportDeclaration: a fully-RESERVED keyword after `import` matches NO
        // clause shape (tsc's `identifier = isIdentifier() ? parseIdentifier() : undefined`
        // stays undefined and `while` is not `*`/`{`/string) — the module-specifier
        // expression parse fails with TS1109 at the keyword, NOTHING is consumed, and the
        // keyword re-parses as its own statement (reservedWords2's
        // `import while = require("dfdf")` emits `require();` + a while-statement).
        // Identifier-capable keywords (strict-reserved like `public`) still take the
        // import-equals / default-import paths below.
        if (isKeyword() && !isIdentifier()) {
            val missingPos = scanner.getPrevTokenEnd()
            reportError("Expression expected.", code = 1109,
                overrideLength = scanner.getTokenText().length)
            parseSemicolon()
            return ImportDeclaration(
                moduleSpecifier = Identifier(text = "", pos = missingPos, end = missingPos),
                modifiers = outerModifiers,
                pos = pos, end = getEnd(), leadingComments = comments,
            )
        }

        // import = require() or import = X.Y
        // (identifier-capable names only — strict-mode reserved words like `public` are
        // identifier-capable and TypeScript parses them as ImportEqualsDeclaration).
        // tsc rule (tokenAfterImportedIdentifierDefinitelyProducesImportDeclaration):
        // after `import <identifier>`, route to import-equals UNLESS the next token is `,`
        // or `from` — so a malformed `import Foo From './Foo'` recovers as `import Foo = From`
        // (TS1005 '=' expected at `From`), matching tsc (declarationEmitUnknownImport2).
        // The extra `!= StringLiteral` keeps the malformed `import Foo "x"` on its prior path.
        if (isIdentifier() && scanner.lookAhead {
                scanner.scan()
                val t = scanner.getToken()
                t == SyntaxKind.Equals ||
                    (t != SyntaxKind.Comma && t != SyntaxKind.FromKeyword && t != SyntaxKind.StringLiteral)
            }) {
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
                    recordModuleSpecifier(expr)
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
            // A same-line string after the module reference (`import Foo = From './Foo'`) is a
            // leftover — `parseSemicolon` is silent on a StringLiteral, so emit TS1005 here so the
            // trailing `'./Foo'` re-parses as its own statement (declarationEmitUnknownImport2). A
            // well-formed `import X = require("s")` consumes the string inside ExternalModuleReference.
            if (token == SyntaxKind.StringLiteral && !scanner.hasPrecedingLineBreak()) {
                reportError("';' expected.", code = 1005,
                    overrideStart = scanner.getTokenPos(), overrideLength = scanner.getTokenText().length)
            }
            // tsc-faithful: `import fs = module("fs")` leaves `(` — TS1005 at the token
            // and the `("fs")` re-parses as its own statement.
            parseSemicolonRequired()
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
            recordModuleSpecifier(spec)
            parseImportAttributes()
            parseSemicolon()
            val trailing = trailingComments()
            return ImportDeclaration(moduleSpecifier = spec, modifiers = outerModifiers, pos = pos, end = getEnd(), leadingComments = comments, trailingComments = trailing)
        }

        // import clause from "module"
        val clause = parseImportClause(isTypeOnly)
        captureIeSlot() // after the clause, before `from`
        val fromOk = parseExpected(SyntaxKind.FromKeyword)
        captureIeSlot() // after `from`, before the module specifier
        if (!fromOk && token != SyntaxKind.StringLiteral) {
            // tsc parseModuleSpecifier: a non-string module specifier is parsed as an
            // EXPRESSION for recovery. When the named-imports list aborted on a numeric/
            // bigint literal (e.g. `import { 0n as foo } from "./foo"` → the list bails on
            // `0n`, `from` is missing, and `0n as foo` is now at the specifier position),
            // consume it as the specifier expression so it does NOT re-parse as a leftover
            // statement (bigintArbirtraryIdentifier). tsc's parseModuleSpecifier ALWAYS parses
            // a non-string specifier as an expression, so consume any token `parseExpression`
            // would advance over — a numeric/bigint literal, an expression-start, a leading
            // binary operator (`import { * } from` → the aborted `*` becomes `<missing> *
            // <missing>`), or a `,` (`import { a }, from "x"` → `<missing> , from`). A
            // statement keyword like `while` (`import * as while from "foo"`) is NOT an
            // expression start / binary operator, so it keeps the missing-node path below and
            // re-parses as its own statement (reservedWords2).
            if (token == SyntaxKind.NumericLiteral || token == SyntaxKind.BigIntLiteral ||
                isStartOfExpression() || getBinaryOperatorPrecedence(token) > 0 ||
                token == SyntaxKind.Comma) {
                val expr = parseExpression()
                val assertClause = parseImportAttributes()
                val assertClausePos = lastImportAttributesPos
                parseSemicolon()
                val trailing = trailingComments()
                return ImportDeclaration(
                    importClause = clause,
                    moduleSpecifier = expr,
                    modifiers = outerModifiers,
                    pos = pos, end = getEnd(), leadingComments = comments,
                    assertClause = assertClause, assertClausePos = assertClausePos,
                    trailingComments = trailing,
                )
            }
            // tsc: with `from` missing and no string follow, the specifier-expression
            // parse fails without consuming (all follow-ups same-start-deduped) — the
            // offending token re-parses as its own statement (`import * as while from
            // "foo"` leaves `while from "foo"` to become a while-statement).
            val missingPos = scanner.getPrevTokenEnd()
            parseSemicolon()
            return ImportDeclaration(
                importClause = clause,
                moduleSpecifier = Identifier(text = "", pos = missingPos, end = missingPos),
                modifiers = outerModifiers,
                pos = pos, end = getEnd(), leadingComments = comments,
            )
        }
        val moduleSpec = parseStringLiteral()
        recordModuleSpecifier(moduleSpec)
        val assertClause = parseImportAttributes()
        val assertClausePos = lastImportAttributesPos
        parseSemicolon()
        val trailing = trailingComments()
        val internal = ieSlots?.takeIf { list -> list.any { it != null } }
        ieSlots = null
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
            internalComments = internal,
        )
    }

    private fun parseImportClause(isTypeOnly: Boolean): ImportClause {
        val pos = getPos()
        var name: Identifier? = null
        var namedBindings: Node? = null

        if (isIdentifier()) {
            name = parseIdentifier()
            // Capture the after-name comment only when a comma follows (i.e. there are bindings);
            // for `import D from "x"` the comment before `from` is captured as the caller's
            // beforeFrom slot instead, avoiding a double-capture of the same buffer.
            if (token == SyntaxKind.Comma) captureIeSlot() // after the default-import name, before `,`
            if (parseOptional(SyntaxKind.Comma)) {
                captureIeSlot() // after `,`, before the named/namespace bindings
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
            captureIeSlot() // after `*`, before `as`
            parseExpected(SyntaxKind.AsKeyword)
            captureIeSlot() // after `as`, before the namespace name
            // `import * as while from "foo"` — TS1359 + a zero-width missing name; the
            // `from`-expected / specifier / ';' follow-ups are all same-start-deduped
            // and `while from "foo"` re-parses as a while-statement (reservedWords2).
            val name = parseDeclarationNameOrMissing()
            NamespaceImport(name = name, pos = pos, end = getEnd())
        } else {
            parseNamedImports()
        }
    }

    // tsc `isListElement(ImportOrExportSpecifiers)`: the current token is a valid
    // import/export specifier start. Bails on `from "..."` (so `import { from "mod"`
    // stops for a better error), accepts a string literal (arbitrary module namespace
    // identifiers), otherwise requires an identifier-or-keyword.
    private fun isImportOrExportSpecifierListElement(): Boolean {
        if (token == SyntaxKind.FromKeyword &&
            scanner.lookAhead { scanner.scan(); scanner.getToken() == SyntaxKind.StringLiteral }) {
            return false
        }
        if (token == SyntaxKind.StringLiteral) return true
        return isIdentifier() || isKeyword()
    }

    // tsc `isInSomeParsingContext()` for the ImportOrExportSpecifiers abort: the offending
    // token is retried against the enclosing contexts. An import/export specifier list is
    // always nested in a statement context (SourceElements/BlockStatements/ModuleBlock), so
    // the token is "in a parsing context" iff it can start a statement (≈ a statement keyword
    // or an expression start, incl. a binary-operator error-tolerance start like `*`) OR it is
    // the list's own terminator `}` OR still a specifier-element candidate. When true, the list
    // aborts WITHOUT consuming the token, so it re-parses as its own statement (tsc
    // `abortParsingListOrMoveToNextToken` → return true); when false, tsc consumes one token
    // (`nextToken`) and continues the list — e.g. a stray `,`.
    private fun isImportSpecifierAbortInEnclosingContext(): Boolean {
        if (token == SyntaxKind.CloseBrace) return true
        if (isImportOrExportSpecifierListElement()) return true
        return canStartStatementForRecovery()
    }

    // tsc `isStartOfStatement()` — approximated for error recovery. Covers statement keywords
    // plus `isStartOfExpression()` (with the binary-operator error-tolerance start). Used only
    // to decide whether a malformed list element re-parses as a statement.
    /** tsc isClassMemberStart (via lookAhead): does the CURRENT token begin a class member?
     *  Used by the list-recovery machinery — the statement-list abort check (a token inside
     *  a nested statement list that an enclosing ClassMembers context claims aborts the inner
     *  lists unconsumed: `case  = bfs.STATEMENTS(4);` after a derailed try re-parses as a
     *  class PROPERTY) and the class-members skip-vs-abort split. */
    private fun isClassMemberStartLookahead(): Boolean = lookAhead {
        var idToken: SyntaxKind? = null
        if (token == SyntaxKind.At) return@lookAhead true
        // Eat up all modifiers; a definite class-member modifier decides immediately.
        while (token == SyntaxKind.PublicKeyword || token == SyntaxKind.PrivateKeyword ||
            token == SyntaxKind.ProtectedKeyword || token == SyntaxKind.StaticKeyword ||
            token == SyntaxKind.AccessorKeyword || token == SyntaxKind.OverrideKeyword ||
            token == SyntaxKind.AbstractKeyword || token == SyntaxKind.AsyncKeyword ||
            token == SyntaxKind.DeclareKeyword || token == SyntaxKind.ReadonlyKeyword ||
            token == SyntaxKind.ExportKeyword || token == SyntaxKind.ConstKeyword) {
            idToken = token
            when (token) {
                SyntaxKind.PublicKeyword, SyntaxKind.PrivateKeyword, SyntaxKind.ProtectedKeyword,
                SyntaxKind.StaticKeyword, SyntaxKind.AccessorKeyword, SyntaxKind.OverrideKeyword ->
                    return@lookAhead true
                else -> {}
            }
            nextToken()
        }
        if (token == SyntaxKind.Asterisk) return@lookAhead true
        // First property-like token following the modifiers (any literal property name).
        if (isIdentifier() || isKeyword() || token == SyntaxKind.StringLiteral ||
            token == SyntaxKind.NumericLiteral || token == SyntaxKind.BigIntLiteral) {
            idToken = token
            nextToken()
        }
        if (token == SyntaxKind.OpenBracket) return@lookAhead true
        if (idToken != null) {
            if (!idToken.name.endsWith("Keyword") || idToken == SyntaxKind.GetKeyword ||
                idToken == SyntaxKind.SetKeyword) return@lookAhead true
            // A keyword name is a member start only when what follows makes it one.
            return@lookAhead when (token) {
                SyntaxKind.OpenParen, SyntaxKind.LessThan, SyntaxKind.Exclamation,
                SyntaxKind.Colon, SyntaxKind.Equals, SyntaxKind.Question -> true
                else -> canParseSemicolon()
            }
        }
        false
    }

    private fun canStartStatementForRecovery(): Boolean = when (token) {
        SyntaxKind.Semicolon, SyntaxKind.At,
        SyntaxKind.VarKeyword, SyntaxKind.LetKeyword, SyntaxKind.UsingKeyword,
        SyntaxKind.FunctionKeyword, SyntaxKind.ClassKeyword, SyntaxKind.EnumKeyword,
        SyntaxKind.IfKeyword, SyntaxKind.DoKeyword, SyntaxKind.WhileKeyword,
        SyntaxKind.ForKeyword, SyntaxKind.ContinueKeyword, SyntaxKind.BreakKeyword,
        SyntaxKind.ReturnKeyword, SyntaxKind.WithKeyword, SyntaxKind.SwitchKeyword,
        SyntaxKind.ThrowKeyword, SyntaxKind.TryKeyword, SyntaxKind.DebuggerKeyword,
        SyntaxKind.CatchKeyword, SyntaxKind.FinallyKeyword,
        SyntaxKind.ImportKeyword, SyntaxKind.ConstKeyword, SyntaxKind.ExportKeyword,
        SyntaxKind.InterfaceKeyword, SyntaxKind.ModuleKeyword, SyntaxKind.NamespaceKeyword,
        SyntaxKind.DeclareKeyword, SyntaxKind.AsyncKeyword, SyntaxKind.TypeKeyword,
        SyntaxKind.GlobalKeyword -> true
        else -> isStartOfExpression() || getBinaryOperatorPrecedence(token) > 0
    }

    // tsc `parseNamedImportsOrExports(NamedImports)` =
    // `parseBracketedList(ImportOrExportSpecifiers, parseImportSpecifier, {, })`, i.e.
    // `parseExpected({) && parseDelimitedList && parseExpected(})`. The faithful recovery
    // (abort-without-consuming a non-specifier token that can start an enclosing statement)
    // is what produces tsc's byte-exact garble for malformed named imports.
    private fun parseNamedImports(): NamedImports {
        val pos = getPos()
        val elements = mutableListOf<ImportSpecifier>()
        if (parseExpected(SyntaxKind.OpenBrace)) {
            captureIeSlot() // after `{`, before the first specifier
            // parseDelimitedList(ImportOrExportSpecifiers, parseImportSpecifier)
            while (true) {
                if (isImportOrExportSpecifierListElement()) {
                    val startFull = scanner.getTokenPos()
                    elements.add(parseImportSpecifier())
                    captureIeSlot() // after the specifier's binding name, before `,`/`}`
                    if (parseOptional(SyntaxKind.Comma)) {
                        captureIeSlot() // after `,`, before the next specifier
                        continue
                    }
                    if (token == SyntaxKind.CloseBrace) break // list terminator
                    // No comma and not terminated: report a comma for a good error, then continue.
                    parseExpected(SyntaxKind.Comma)
                    if (startFull == scanner.getTokenPos()) nextToken() // avoid an infinite loop
                    continue
                }
                if (token == SyntaxKind.CloseBrace) break
                // abortParsingListOrMoveToNextToken(ImportOrExportSpecifiers)
                if (token == SyntaxKind.FromKeyword) {
                    reportError("'}' expected.", code = 1005)
                } else {
                    reportError("Identifier expected.", code = 1003)
                }
                if (isImportSpecifierAbortInEnclosingContext()) break
                nextToken()
            }
            parseExpected(SyntaxKind.CloseBrace)
        }
        return NamedImports(elements = elements, pos = pos, end = getEnd())
    }

    private fun parseImportSpecifier(): ImportSpecifier {
        val pos = getPos()
        val isTypeOnly = isIdentifier() && scanner.getTokenValue() == "type" && scanner.lookAhead {
            scanner.scan()
            isIdentifier()
        }
        if (isTypeOnly) nextToken()

        // tsc parseImportOrExportSpecifier (kind == ImportSpecifier): the BINDING name —
        // the sole name when there is no `as`, otherwise the name after `as` — must be a
        // valid binding identifier. A keyword that is NOT an identifier-in-context (e.g.
        // `default`) → TS1003 "Identifier expected." at that name. `yield`/`await` and the
        // strict-future-reserved words (`private`/`public`/…) ARE identifiers here, so they
        // do NOT trip TS1003 (the strict-mode-reserved words go through TS1214 in the
        // checker; `yield` is exempt there). See es6ImportNamedImportIdentifiersParsing /
        // strictModeWordInImportDeclaration.
        var bindKwStart = scanner.getTokenPos()
        var bindKwLen = scanner.getTokenText().length
        var bindIsKwNotIdent = isKeyword() && !isIdentifier()

        val first = parseModuleExportNameOrMissing()
        if (token == SyntaxKind.AsKeyword) captureIeSlot() // after propertyName, before `as`
        return if (parseOptional(SyntaxKind.AsKeyword)) {
            captureIeSlot() // after `as`, before the binding name
            bindKwStart = scanner.getTokenPos()
            bindKwLen = scanner.getTokenText().length
            bindIsKwNotIdent = isKeyword() && !isIdentifier()
            val name = parseIdentifier()
            if (bindIsKwNotIdent) reportError("Identifier expected.", code = 1003,
                overrideStart = bindKwStart, overrideLength = bindKwLen)
            ImportSpecifier(propertyName = first, name = name, isTypeOnly = isTypeOnly, pos = pos, end = getEnd())
        } else {
            if (bindIsKwNotIdent) reportError("Identifier expected.", code = 1003,
                overrideStart = bindKwStart, overrideLength = bindKwLen)
            ImportSpecifier(name = first, isTypeOnly = isTypeOnly, pos = pos, end = getEnd())
        }
    }

    private fun parseExportDeclaration(): Statement {
        val pos = getPos()
        val comments = leadingComments()
        ieSlots = mutableListOf()
        parseExpected(SyntaxKind.ExportKeyword)
        captureIeSlot() // after `export`, before default/`*`/`{`/specifier

        // export default
        if (parseOptional(SyntaxKind.DefaultKeyword)) {
            captureIeSlot() // after `default`, before the exported value
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

                AsyncKeyword if lookAhead { nextToken(); token == SyntaxKind.FunctionKeyword && !scanner.hasPrecedingLineBreak() } -> {
                    // `export default async function foo()` — parse as FunctionDeclaration with Async modifier
                    // `export default async function*` — same. B329: `async` NOT followed by
                    // a same-line `function` is an EXPRESSION (`export default async(...)` is
                    // a CALL of the identifier `async`; bare `export default async;` exports
                    // the identifier) — falls to the expression arm below.
                    nextToken()
                    parseFunctionDeclarationOrExpression(modifiers + ModifierFlag.Async, comments)
                }

                else -> {
                    val expr = parseAssignmentExpression()
                    captureIeSlot() // after the exported value, before `;`
                    parseSemicolon()
                    val trailing = trailingComments()
                    val internal = ieSlots?.takeIf { list -> list.any { it != null } }
                    ieSlots = null
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
                        internalComments = internal,
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
            captureIeSlot() // after `*`, before `as`/`from`
            val nsExport = if (parseOptional(SyntaxKind.AsKeyword)) {
                captureIeSlot() // after `as`, before the namespace name
                val nsName = parseIdentifier()
                captureIeSlot() // after the namespace name, before `from`
                NamespaceExport(name = nsName, pos = pos, end = getEnd())
            } else null
            parseExpected(SyntaxKind.FromKeyword)
            captureIeSlot() // after `from`, before the module specifier
            if (token != SyntaxKind.StringLiteral) {
                reportError("String literal expected.", code = 1141)
            }
            val spec = parseStringLiteral()
            recordModuleSpecifier(spec)
            val assertClauseNs = parseImportAttributes()
            val assertClauseNsPos = lastImportAttributesPos
            parseSemicolon()
            val internal = ieSlots?.takeIf { list -> list.any { it != null } }
            ieSlots = null
            return ExportDeclaration(
                exportClause = nsExport,
                moduleSpecifier = spec,
                isTypeOnly = isTypeOnly,
                pos = pos,
                end = getEnd(),
                leadingComments = comments,
                assertClause = assertClauseNs,
                assertClausePos = assertClauseNsPos,
                internalComments = internal,
            )
        }

        // export { ... } from? "module"
        if (token == SyntaxKind.OpenBrace) {
            val namedExports = parseNamedExports()
            captureIeSlot() // after `}`, before `from`
            val moduleSpec = if (parseOptional(SyntaxKind.FromKeyword)) {
                captureIeSlot() // after `from`, before the module specifier
                if (token != SyntaxKind.StringLiteral) {
                    reportError("String literal expected.", code = 1141)
                }
                parseStringLiteral().also { recordModuleSpecifier(it) }
            } else null
            val assertClauseNamed = if (moduleSpec != null) parseImportAttributes() else null
            val assertClauseNamedPos = if (moduleSpec != null) lastImportAttributesPos else -1
            parseSemicolon()
            val internal = ieSlots?.takeIf { list -> list.any { it != null } }
            ieSlots = null
            return ExportDeclaration(
                exportClause = namedExports,
                moduleSpecifier = moduleSpec,
                isTypeOnly = isTypeOnly,
                pos = pos,
                end = getEnd(),
                leadingComments = comments,
                assertClause = assertClauseNamed,
                assertClausePos = assertClauseNamedPos,
                trailingComments = trailingComments(),
                internalComments = internal,
            )
        }

        // export var/let/const/function/class/interface/type/enum/namespace/declare/abstract/async/import
        val modifiers = setOf(ModifierFlag.Export)
        return when (token) {
            VarKeyword, LetKeyword -> parseVariableStatement(modifiers, comments)
            // `export using x = e;` PARSES and is then a checker error (TS1491) — tsc's
            // `parseDeclarationWorker` takes the modifiers and `checkGrammarModifiers`
            // rejects them, so the declaration still binds and its body still checks.
            UsingKeyword if isUsingDeclaration() -> parseVariableStatement(modifiers, comments)
            AwaitKeyword if isAwaitUsingDeclaration() -> parseVariableStatement(modifiers, comments)
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
            // tsc isStartOfDeclaration: a contextual-keyword declaration after `export` still
            // requires a valid same-line name — `export namespace/interface/type 100 {}` is NOT a
            // declaration; the export is dropped and the tokens re-parse as expression statements
            // (parseInvalidNames). Mirror the parseStatement guards so no malformed (erased)
            // declaration node is created (which would wrongly flip module detection → `export {}`).
            InterfaceKeyword -> if (lookAhead { nextToken(); !scanner.hasPrecedingLineBreak() && isIdentifier() }) {
                parseInterfaceDeclaration(modifiers, comments)
            } else parseExpressionStatement()
            TypeKeyword -> if (isStartOfTypeAlias()) parseTypeAliasDeclaration(modifiers, comments)
                else parseExpressionStatement()
            EnumKeyword -> parseEnumDeclaration(modifiers, comments)
            NamespaceKeyword, ModuleKeyword -> if (lookAhead {
                    nextToken()
                    !scanner.hasPrecedingLineBreak() && (isIdentifier() || token == SyntaxKind.StringLiteral)
                }) {
                parseModuleDeclaration(modifiers, comments)
            } else parseExpressionStatement()
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

            else -> {
                // B505: `export <bogus-modifier> <declaration>` (e.g. `export extension class C`)
                // — tsc treats the dangling `export` (no real declaration following) as
                // "Declaration or statement expected." at the `export` keyword, then re-parses
                // the bogus identifier as an expression statement and the trailing declaration
                // on its own. Tight FP gate: the token is an identifier AND the NEXT token is a
                // declaration-start keyword — exactly the bogus-modifier-before-declaration shape.
                // Plain `export foo;` (identifier not followed by a declaration) keeps the prior
                // recovery (no TS1128). Require a TRUE Identifier token (not a contextual keyword)
                // so the UMD `export as namespace X` form (`as` is AsKeyword followed by
                // `namespace`) is NOT misread as a bogus-modifier shape.
                if (token == SyntaxKind.Identifier && lookAhead {
                        nextToken()
                        token == ClassKeyword || token == InterfaceKeyword ||
                            token == FunctionKeyword || token == EnumKeyword ||
                            token == NamespaceKeyword || token == ModuleKeyword ||
                            token == AbstractKeyword || token == VarKeyword ||
                            token == LetKeyword || token == ConstKeyword || token == TypeKeyword
                    }) {
                    reportError(
                        "Declaration or statement expected.",
                        code = 1128, overrideStart = pos, overrideLength = 6,
                    )
                }
                parseExpressionStatement()
            }
        }
    }

    private fun parseNamedExports(): NamedExports {
        val pos = getPos()
        parseExpected(SyntaxKind.OpenBrace)
        captureIeSlot() // after `{`, before the first specifier
        val elements = mutableListOf<ExportSpecifier>()
        while (token != SyntaxKind.CloseBrace && token != SyntaxKind.EndOfFile) {
            // `from` is a CONTEXTUAL keyword and an ordinary specifier name inside the clause
            // (`export { from } from './x'` — rxjs's own index.d.ts, line 43). Only a `from`
            // that is FOLLOWED BY A STRING LITERAL means the `}` is missing (tsc
            // `isListElement(ImportOrExportSpecifiers)`): stop there so that
            // parseExpected(CloseBrace) reports '}' expected at `from`.
            if (token == SyntaxKind.FromKeyword && !isImportOrExportSpecifierListElement()) break
            elements.add(parseExportSpecifier())
            captureIeSlot() // after the specifier's binding name, before `,`/`}`
            if (token == SyntaxKind.CloseBrace || token == SyntaxKind.EndOfFile) break
            // If we see `from` keyword after a specifier (no comma before it),
            // report ',' expected at the `from` position (matching TypeScript behavior).
            if (token == SyntaxKind.FromKeyword) {
                parseExpected(SyntaxKind.Comma) // reports ',' expected at current (from) position
                break
            }
            if (!parseOptional(SyntaxKind.Comma)) break
            captureIeSlot() // after `,`, before the next specifier
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
        val first = parseModuleExportNameOrMissing()
        if (token == SyntaxKind.AsKeyword) captureIeSlot() // after propertyName, before `as`
        return if (parseOptional(SyntaxKind.AsKeyword)) {
            captureIeSlot() // after `as`, before the binding name
            ExportSpecifier(
                propertyName = first,
                name = parseModuleExportNameOrMissing(),
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
            // tsc doInDecoratorContext: while parsing the decorator expression, a `[` is
            // NOT an element access — it could be the start of a ComputedPropertyName
            // (`@x ["property"]: any;` — the decorator is `x`, the `[` belongs to the
            // member name). parseArgumentList resets the flag (tsc doOutsideOfContext),
            // so `@dec(arr[0])` still parses the inner element access.
            val savedDecoratorContext = inDecoratorContext
            inDecoratorContext = true
            val expr = try { parseLeftHandSideExpression() } finally { inDecoratorContext = savedDecoratorContext }
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

        // Parenthesized arrows are allowed only at this (assignment) level. Binary operands
        // set parenArrowAllowed=false; nested assignment contexts (args, paren, branches) reset
        // it here. Save/restore so the caller's context is preserved.
        val savedParenArrow = parenArrowAllowed
        parenArrowAllowed = true
        try {
        val expr = parseConditionalExpression()

        if (isAssignmentOperator(token)) {
            // Error recovery: if LHS is a missing identifier (no valid expression start)
            // at the very START of an expression statement, skip the assignment operator
            // and return just the RHS — tsc's statement-level recovery skips the orphan
            // `=` with a deduped TS1128 and parses `fn()` as its own statement
            // (`{ a, b } = fn()` → block + `fn();`, destructionAssignmentError). In any
            // NESTED expression context the assignment BINDS with the missing LHS
            // (tsc isLeftHandSideExpression accepts Identifier — `while ( = require(""))`
            // keeps `<missing> = require("")` as the condition, reservedWords2).
            // The skip applies ONLY to list-born statements — a FORCED body (if/while/…)
            // binds `<missing> ^= rhs` like tsc's unconditional parseStatement does
            // (constructorWithIncompleteTypeAnnotation line 22).
            if (expr is Identifier && expr.text.isEmpty() && expr.pos == exprStatementStartPos &&
                !exprStatementForced) {
                nextToken() // skip the invalid assignment operator
                return parseAssignmentExpression()
            }
            // tsc parseAssignmentExpressionOrHigher: the assignment operator binds only
            // when the parsed LHS is a LeftHandSideExpression KIND. A Binary/Conditional
            // LHS never continues as an assignment — e.g. in `( y = z === = 'function')`
            // the `= 'function'` must NOT bind to `z === <missing>`; the paren closes
            // (')' expected, same-start-deduped) and the OUTER paren-LHS assignment binds.
            if (expr is BinaryExpression || expr is ConditionalExpression ||
                expr is TypeOfExpression || expr is VoidExpression ||
                expr is DeleteExpression || expr is AwaitExpression ||
                expr is PrefixUnaryExpression) {
                // (B332: unary expressions aren't LeftHandSideExpression kinds either —
                // `typeof = 10` keeps `typeof <missing>` as its own statement and the
                // orphan `= 10` re-parses; tsc emits `typeof ;` + `10;`.)
                return expr
            }
            val op = token
            nextToken()
            val right = parseAssignmentExpression()
            return BinaryExpression(left = expr, operator = op, right = right, pos = expr.pos, end = getEnd())
        }

        return expr
        } finally {
            parenArrowAllowed = savedParenArrow
        }
    }

    private fun parseConditionalExpression(): Expression {
        val expr = parseBinaryExpression(0)
        if (token != SyntaxKind.Question) return expr
        // tsc: an (unparenthesized) arrow function is an ASSIGNMENT-level expression —
        // it can never be a conditional's condition. `(a) => {} ? b : c` ends the
        // statement at the arrow (';' expected at the `?`); a PARENTHESIZED arrow
        // condition still binds normally. An EMPTY error-recovery Identifier condition
        // bails too — the no-progress statement recovery then skips the `?` and
        // discards, instead of keeping a garbage ` ? x : y` conditional.
        if (expr is ArrowFunction || (expr is Identifier && expr.text.isEmpty())) return expr
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
        // NOTE: `as`/`satisfies` are handled by the precedence-respecting loop below
        // (they have precedence 7 — the loop attaches them only when `7 > minPrec`).
        // A greedy `parseExpressionSuffix(left)` call here used to attach them to the
        // bare unary operand BEFORE the binary loop, IGNORING precedence, so a right
        // operand `a + b as T` mis-parsed as `a + (b as T)` instead of tsc's `(a + b)
        // as T` — the whole `+` result is the cast source (binder.ts's
        // `tokenToString(op) + operand.text as __String` FP'd `string ⊄ __String`).
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
            // A binary right operand is NOT at assignment level → a parenthesized arrow there
            // (`a << (x) => y`) stays `(a << (x))` + leftover `=> y` (tsc).
            val savedPAA = parenArrowAllowed
            parenArrowAllowed = false
            val right = parseBinaryExpression(nextMinPrec)
            parenArrowAllowed = savedPAA
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
        val mustBeUnaryJsx = jsxMustBeUnary
        jsxMustBeUnary = false
        val comments = leadingComments()
        return when (token) {
            PlusPlus, MinusMinus -> {
                val op = token; nextToken()
                jsxMustBeUnary = true
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
                jsxMustBeUnary = true
                PrefixUnaryExpression(
                    operator = op,
                    operand = parseUnaryExpression(),
                    pos = pos,
                    end = getEnd(),
                    leadingComments = comments
                )
            }

            DeleteKeyword -> {
                nextToken()
                jsxMustBeUnary = true
                DeleteExpression(
                    expression = parseUnaryExpression(),
                    pos = pos,
                    end = getEnd(),
                    leadingComments = comments
                )
            }

            TypeOfKeyword -> {
                nextToken()
                jsxMustBeUnary = true
                TypeOfExpression(
                    expression = parseUnaryExpression(),
                    pos = pos,
                    end = getEnd(),
                    leadingComments = comments
                )
            }

            VoidKeyword -> {
                nextToken()
                jsxMustBeUnary = true
                VoidExpression(
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
                    if (functionLikeDepth == 0) sawTopLevelAwaitExpr = true
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
                        return parseJsxElementOrFragment(mustBeUnary = mustBeUnaryJsx)
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
        parseExpected(SyntaxKind.GreaterThan)
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
    private fun parseJsxElementOrFragment(topBadPos: Int? = null, mustBeUnary: Boolean = false): Expression {
        val pos = getPos()
        val isOutermostJsx = jsxElementDepth == 0
        jsxElementDepth++
        val result = try {
            parseJsxElementOrFragmentBody(pos, isOutermostJsx)
        } finally {
            jsxElementDepth--
        }
        // tsc parseJsxElementOrSelfClosingElementOrFragment tail: in EXPRESSION context
        // a `<` right after a JSX element is a sibling with a missing comma/parent —
        // parse it, wrap both in `Binary(result, missing-comma, invalid)`, and report
        // TS2657 "JSX expressions must have one parent element." spanning from the
        // FIRST element (topBadPos threads through chains; the same-start dedup
        // collapses the chained reports).
        if (!mustBeUnary && isOutermostJsx && token == SyntaxKind.LessThan) {
            val isClosing = lookAhead {
                scanner.scan()
                scanner.getToken() == SyntaxKind.Slash
            }
            if (!isClosing) {
                val badPos = topBadPos ?: pos
                val invalid = parseJsxElementOrFragment(badPos)
                // tsc parseErrorAt(topBadPos, invalidElement.end): an invalid element
                // consumed to EOF (unclosed) spans through the end of the source. tsc's
                // corpus fixtures end with CRLF while our multi-file parse normalizes to
                // LF — the baseline's squiggle arithmetic counts the 2-char line ending,
                // so add 1 when the source ends with a (normalized) newline to reproduce
                // the empty-last-line continuation rendering.
                val invalidEnd = if (token == SyntaxKind.EndOfFile) {
                    source.length + (if (source.endsWith("\n")) 1 else 0)
                } else scanner.getPrevTokenEnd()
                reportError(
                    "JSX expressions must have one parent element.", code = 2657,
                    overrideStart = badPos, overrideLength = invalidEnd - badPos,
                )
                return BinaryExpression(left = result, operator = SyntaxKind.Comma, right = invalid, pos = pos, end = getEnd())
            }
        }
        return result
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
        val afterLt = scanner.getPrevTokenEnd() // position right after the consumed `<`
        // A JSX tag token that is NOT an identifier/keyword nor `{` (a number, `<`, EOF,
        // string, …) is a FAILED tag: tsc parseJsxElementName→parseIdentifierName emits
        // TS1003 "Identifier expected." at the offending token, leaves it UNCONSUMED, and
        // recovers as a self-closing element; the token re-parses as an ordinary
        // expression. The TS17004 span is end-of-`<` whether emitted before or after the
        // (non-advancing) parseExpected recovery, so emit it FIRST here: tsc emits TS17004
        // from the CHECKER, keeping its TS1003 and any later same-position diagnostic (e.g.
        // a const-decl `;`-expected) adjacent so they same-start-dedup. Emitting our
        // parser-side TS17004 AFTER the TS1003 would interleave it and break that dedup.
        val tagFailed = token != SyntaxKind.OpenBrace && !isIdentifierToken(token) && !isKeyword()
        if (tagFailed) emitTs17004IfNeeded(pos, isOutermostJsx)
        val tagName = if (token == SyntaxKind.OpenBrace) {
            val emptyTagPos = scanner.getPrevTokenEnd()
            reportError("Identifier expected.", code = 1003)
            Identifier(text = "", pos = emptyTagPos, end = emptyTagPos + 1)
        } else if (tagFailed) {
            reportError("Identifier expected.", code = 1003)
            Identifier(text = "", pos = afterLt, end = afterLt)
        } else {
            parseJsxTagName()
        }

        // Parse attributes. tsc's parseList(JsxAttributes) aborts immediately on a
        // non-`{`/non-identifier token, so a failed tag never consumes the offending
        // token as a boolean-attribute shorthand.
        val attributes = if (tagFailed) emptyList() else parseJsxAttributes()
        if (!tagFailed) maybeSplitJsxGreaterThan()

        return if (tagFailed) {
            // tsc else-branch: a failed tag → self-closing element. `/`- and `>`-expected
            // both land at the (unconsumed) offending token, sharing the TS1003's start,
            // so the same-start dedup suppresses both — no extra diagnostics. The token
            // stays unconsumed for the surrounding expression parser to re-scan.
            parseExpected(SyntaxKind.Slash)
            parseExpected(SyntaxKind.GreaterThan)
            JsxSelfClosingElement(tagName = tagName, attributes = attributes, pos = pos, end = afterLt)
        } else if (token == SyntaxKind.Slash) {
            // Self-closing: <Tag attrs/>
            nextToken() // consume /
            parseExpected(SyntaxKind.GreaterThan)
            emitTs17004IfNeeded(pos, isOutermostJsx)
            JsxSelfClosingElement(tagName = tagName, attributes = attributes, pos = pos, end = getEnd())
        } else if (token != SyntaxKind.GreaterThan && token != SyntaxKind.EndOfFile) {
            // tsc parseJsxOpeningOrSelfClosingElementOrOpeningFragment: after the
            // attributes, a token that is neither `>` nor `/` recovers as a
            // SELF-CLOSING element — parseExpected(Slash) + parseExpected(GreaterThan)
            // both land on the unconsumed offending token (same-start-deduped against
            // the attribute recovery's TS1003), which re-parses in the surrounding
            // expression (`<Foo<number>>` in a .jsx → `<Foo />` + the leftover
            // `<number>>...` re-parses via the missing-comma recovery).
            emitTs17004IfNeeded(pos, isOutermostJsx)
            parseExpected(SyntaxKind.Slash)
            parseExpected(SyntaxKind.GreaterThan)
            JsxSelfClosingElement(tagName = tagName, attributes = attributes, pos = pos, end = scanner.getPrevTokenEnd())
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
                // tsc: a closing tag not matching the opening one → TS17002 at the
                // closing tag name (FP-safe: tsc always errors on the mismatch). Skip when
                // either side is a recovery-synthesized EMPTY name — the mismatch is our
                // recovery artifact, not a source-level tag mismatch.
                if (jsxTagNameToString(closingTagName) != jsxTagNameToString(tagName) &&
                    jsxTagNameToString(closingTagName).isNotEmpty() && jsxTagNameToString(tagName).isNotEmpty()) {
                    reportError(
                        "Expected corresponding JSX closing tag for '${jsxTagNameToString(tagName)}'.",
                        code = 17002,
                        overrideStart = closingTagName.pos,
                        overrideLength = jsxTagNameLength(closingTagName),
                    )
                }
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
        // (GATE.2) `getEnd()` AFTER the `nextToken()`, so the tag name carries the
        // end of the FOLLOWING token like every other node in this parser. Read
        // before it, the end is already exact, and a reader that snaps it back to
        // the token stream (`SourceIndex.realEndOf`) lands on the token BEFORE the
        // name — an empty span no descent can enter, so a caret on `div` answered
        // about the whole opening element.
        nextToken()
        val id = Identifier(text = text, pos = pos, end = getEnd())
        // Handle qualified name: Foo.Bar.Baz
        val tagName: Expression = if (token == SyntaxKind.Dot) {
            var expr: Expression = id
            while (token == SyntaxKind.Dot) {
                nextToken() // consume .
                val rightPos = getPos()
                val rightText = scanner.getTokenValue()
                // (GATE.2) after the `nextToken()`, as above.
                nextToken()
                val right = Identifier(text = rightText, pos = rightPos, end = getEnd())
                expr = PropertyAccessExpression(expression = expr, name = right, pos = pos, end = getEnd())
            }
            expr
        } else {
            id
        }
        // Skip optional type arguments: <T>, <'bar'>, <T extends X>, etc.
        // These are TypeScript-specific and get stripped during transformation.
        // tsc: NOT parsed in JavaScript files (`<Foo<number>>` in a .jsx file leaves
        // the `<` for the attribute-list recovery).
        if (token == SyntaxKind.LessThan && !isJsLikeFile) {
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
    /**
     * tsc's scanner emits a SINGLE `>` (shift/compare compounds come from
     * reScanGreaterToken at binary-operator positions only) — ours scans compounds
     * directly, so inside a JSX tag `<number>>` shows up as GreaterThanGreaterThan.
     * Split the compound: reposition right after the first `>` and treat the token
     * as GreaterThan so the tag closes there (the rest re-scans as JSX text/children).
     */
    private fun maybeSplitJsxGreaterThan() {
        val isCompound = token == SyntaxKind.GreaterThanGreaterThan ||
            token == SyntaxKind.GreaterThanGreaterThanGreaterThan ||
            token == SyntaxKind.GreaterThanEquals ||
            token == SyntaxKind.GreaterThanGreaterThanEquals ||
            token == SyntaxKind.GreaterThanGreaterThanGreaterThanEquals
        if (!isCompound) return
        scanner.resetToPosition(scanner.getTokenPos() + 1)
        token = SyntaxKind.GreaterThan
    }

    private fun parseJsxAttributes(): List<Node> {
        val attributes = mutableListOf<Node>()
        while (true) {
            maybeSplitJsxGreaterThan()
            if (token == SyntaxKind.Slash || token == SyntaxKind.GreaterThan ||
                token == SyntaxKind.EndOfFile) break
            // tsc parseList(JsxAttributes): a token that cannot start an attribute
            // (identifier/keyword/`{`) reports TS1003 "Identifier expected." and either
            // ABORTS without consuming (the token can start an enclosing context — e.g.
            // the `<` of `<Foo<number>>` in a .jsx file) or skips it and continues.
            if (token != SyntaxKind.OpenBrace && !isIdentifierToken(token) && !isKeyword()) {
                reportError("Identifier expected.", code = 1003)
                if (canStartStatementForRecovery()) break
                nextToken()
                continue
            }
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
                // tsc scanJsxToken: a bare `>` / `}` inside JSX text is an error at each
                // occurrence (FP-safe: tsc ALWAYS errors, so no passing baseline carries
                // an unflagged bare `>`/`}` in JSX text).
                for ((i, ch) in rawText.withIndex()) {
                    if (ch == '>') reportError(
                        "Unexpected token. Did you mean `{'>'}` or `&gt;`?", code = 1382,
                        overrideStart = textStart + i, overrideLength = 1,
                    ) else if (ch == '}') reportError(
                        "Unexpected token. Did you mean `{'}'}` or `&rbrace;`?", code = 1381,
                        overrideStart = textStart + i, overrideLength = 1,
                    )
                }
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
                    val callArgs = mutableListOf(arg)
                    if (token == Comma) {
                        val commaPos = getPos()
                        nextToken()
                        // Trailing comma `import(spec,)` — TS1009. Second-arg (import options /
                        // attributes) is TypeScript 5.3+; the trailing-comma-before-`)` form is
                        // still an error.
                        if (token == CloseParen) {
                            reportError("Trailing comma not allowed.", code = 1009,
                                overrideStart = commaPos, overrideLength = 1)
                        } else {
                            // B397: import attributes/options object — parse it (else CloseParen
                            // fails on `{`), and flag a deprecated `assert` clause (TS2880).
                            val secondArg = parseAssignmentExpression()
                            callArgs.add(secondArg)
                            emitImportAttrAssertDeprecation(secondArg, typePosition = false)
                            if (token == Comma) nextToken()
                        }
                    }
                    parseExpected(CloseParen)
                    recordModuleSpecifier(callArgs.firstOrNull())
                    CallExpression(
                        expression = Identifier("import", pos = pos),
                        arguments = callArgs,
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
            // tsc parses arrows at ASSIGNMENT level — a `.` after a BARE (unparenthesized)
            // arrow node can only arise in error recovery (e.g. the missing-'=>' zero-width
            // body) and belongs to the ENCLOSING context (`((a) => b).c` needs the parens).
            if (result is ArrowFunction && token == Dot) break
            // tsc parseMemberExpressionRest: in the [Decorator] context a `[` is NOT an
            // element access — it could be part of a ComputedPropertyName (`@x [Symbol.iterator]:
            // any;`). `?.[` (QuestionDot token) is unaffected, matching tsc.
            if (inDecoratorContext && token == OpenBracket) return result
            // tsc parseTypeArgumentsInExpression: type arguments must NOT be parsed in
            // JavaScript files (ambiguity with binary operators) — `Foo<number>()` in a
            // .js/.jsx file is the comparison chain `Foo < number > ()`.
            if (isJsLikeFile && (token == LessThan || token == LessThanLessThan)) return result
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
                    // propertyAccessExpressionInnerComments: a same-line comment between the receiver
                    // and the dot (`Array /*2*/. x`) rides the receiver's trailingComments — so the
                    // `?.` desugar (which reuses the receiver node 3×) duplicates it, matching tsc.
                    // Gated to a bare Identifier receiver: a CallExpression uses `callTrailing` for
                    // this position, and numeric literals self-emit their trailing comments.
                    val recvInlineTrailing = if (!newLineBefore && result is Identifier && result.trailingComments.isNullOrEmpty()) {
                        scanner.getTrailingComments()?.takeIf { it.isNotEmpty() }
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
                        // tsc parseRightSideOfDot: a newline-dot followed by an identifier-OR-keyword
                        // that is itself followed by another identifier/keyword ON THE SAME LINE means
                        // the token starts a new statement (no ASI between the two), so the property
                        // name is missing. This must fire even when the token is a CONTEXTUAL keyword
                        // that `isIdentifierToken` accepts (e.g. `namespace`/`type`) — gating on
                        // `!isIdentifier()` wrongly consumes `namespace`/`type` as the property name and
                        // derails recovery of the following declaration (errorRecoveryWithDotFollowedByNamespaceKeyword).
                        newLineAfterDot && (isIdentifier() || isKeyword()) &&
                                lookAhead { nextToken(); (isIdentifier() || isKeyword()) && !scanner.hasPrecedingLineBreak() } -> {
                            // Statement-starting token → report at afterDotPos (right after the dot)
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
                    // propertyAccessExpressionInnerComments: keep the newline-preceded dot-leading
                    // comments SEPARATE from the name's after-dot leading comments. The emitter emits
                    // preDotComments before the dot and name.leadingComments after it.
                    val recv = if (recvInlineTrailing != null) result.withTrailingComments(recvInlineTrailing) else result
                    PropertyAccessExpression(expression = recv, name = name, newLineBefore = newLineBefore, newLineAfterDot = newLineAfterDot, preDotComments = dotLeadingCommentsList, expressionTrailingLineComments = expressionTrailingLineComments, pos = recv.pos, end = getEnd())
                }

                OpenBracket -> {
                    // Capture comments INTERNAL to the element access (between object/`[`/arg/`]`)
                    // before each clearing nextToken/parseExpected wipes the scanner buffers — these
                    // are otherwise lost (elementAccessExpressionInternalComments).
                    val preBracket = combineCommentBuffers()
                    nextToken()
                    val argLeading = combineCommentBuffers()
                    val arg = if (token == SyntaxKind.CloseBracket) {
                        // TS1011: empty element access a[] is invalid
                        // Report at position right after `[` (prevTokenEnd), length 0
                        reportError("An element access expression should take an argument.", code = 1011, overrideLength = 0, overrideStart = scanner.getPrevTokenEnd())
                        OmittedExpression(pos = getPos(), end = getPos())
                    } else {
                        parseExpression()
                    }
                    val preClose = combineCommentBuffers()
                    parseExpected(SyntaxKind.CloseBracket)
                    ElementAccessExpression(
                        expression = result,
                        argumentExpression = arg,
                        preBracketComments = preBracket,
                        argLeadingComments = argLeading,
                        preCloseBracketComments = preClose,
                        pos = result.pos,
                        end = getEnd()
                    )
                }

                OpenParen -> {
                    val args = parseArgumentList()
                    val innerComments = lastCallInnerComments
                    recordCallModuleSpecifier(result, args)
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
                        sawJsDocInTypeArgs = false
                        val typeArgs = tryParseTypeArguments()
                        if (typeArgs != null) typeArgsEnd = scanner.getPrevTokenEnd()
                        when {
                            typeArgs != null && token == SyntaxKind.OpenParen -> {
                                val args = parseArgumentList()
                                recordCallModuleSpecifier(result, args)
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
                            // Heritage base spine (`extends Foo<T>, Bar`): do NOT collapse
                            // `Foo<T>` into a value-position instantiation expr (which drops the
                            // type args). Bail so tryScan restores the scanner and
                            // parseExpressionWithTypeArguments re-reads `<T>` as heritage type
                            // arguments. Gated to the instantiation case (a `(`/template already
                            // produced a Call/TaggedTemplate above, so a genuine heritage call
                            // `extends mixin<T>()` is unaffected).
                            typeArgs != null && parsingHeritageBase -> null
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
                                        // expressionWithJSDocTypeArguments: preserve JSDoc-`?`
                                        // type args (`foo<?string>`) for value-position JS emit.
                                        instantiationJsDocTypeArgsText =
                                            if (sawJsDocInTypeArgs && typeArgsEnd > typeArgsStart)
                                                normalizeJsDocTypeArgs(source.substring(typeArgsStart, typeArgsEnd))
                                            else null,
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
                    // propertyAccessExpressionInnerComments: capture comments around `?.` BEFORE
                    // consuming it (same model as the `.` branch). Only the property-access
                    // sub-branches (else / LessThan-fallback) below consume these; the
                    // OpenParen/OpenBracket call/index sub-branches ignore them (byte-identical).
                    val qNewLineBefore = scanner.hasPrecedingLineBreak()
                    val qDotLeading = if (qNewLineBefore) leadingComments() else null
                    val qRecvInlineTrailing = if (!qNewLineBefore && result is Identifier && result.trailingComments.isNullOrEmpty()) {
                        scanner.getTrailingComments()?.takeIf { it.isNotEmpty() }
                    } else null
                    nextToken()
                    val qNewLineAfterDot = scanner.hasPrecedingLineBreak()
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
                            val callExpr: Expression? = if (isJsLikeFile) null else scanner.tryScan {
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
                                val recv = if (qRecvInlineTrailing != null) result.withTrailingComments(qRecvInlineTrailing) else result
                                PropertyAccessExpression(
                                    expression = recv,
                                    name = name,
                                    questionDotToken = true,
                                    newLineBefore = qNewLineBefore,
                                    newLineAfterDot = qNewLineAfterDot,
                                    preDotComments = qDotLeading,
                                    pos = recv.pos,
                                    end = getEnd()
                                )
                            }
                        }

                        else -> {
                            val name = parseIdentifierName()
                            val recv = if (qRecvInlineTrailing != null) result.withTrailingComments(qRecvInlineTrailing) else result
                            PropertyAccessExpression(
                                expression = recv,
                                name = name,
                                questionDotToken = true,
                                newLineBefore = qNewLineBefore,
                                newLineAfterDot = qNewLineAfterDot,
                                preDotComments = qDotLeading,
                                pos = recv.pos,
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
        // tsc: `new <` in EXPRESSION position is NOT leading type arguments — the callee
        // primary parse at `<` yields a MISSING identifier (TS1109 "Expression expected."
        // at the `<`, not consumed) and the NewExpression(new, missing) becomes the LEFT
        // operand of the following comparison chain: `new <any>Test2()` misparses as
        // `(new <missing> < any) > Test2()` (newExpressionWithCast).
        if (token == LessThan) {
            reportError("Expression expected.", code = 1109, overrideLength = 1)
            val missing = Identifier(text = "", pos = getPos(), end = getPos())
            return NewExpression(expression = missing, typeArguments = null,
                leadingTypeArguments = null, arguments = null, pos = pos, end = getPos())
        }
        val leadingTypeArgs: List<TypeNode>? = null
        // For `new`, the constructor expression allows member access (. and []) and nested `new`,
        // but NOT function calls. parseCallAndAccess would greedily consume `()` and turn
        // `new Foo()` into `new (Foo())` — use parseMemberAccessOnly instead.
        // Handle nested `new` (e.g. `new new Date`) by recursing.
        val baseExpr = if (token == NewKeyword) parseNewExpression() else parsePrimaryExpression()
        val expr = parseMemberAccessOnly(baseExpr)
        // Only parse trailing type args if we didn't find leading ones (e.g. `new Foo<T>()`)
        val typeArgs = if (leadingTypeArgs == null && !isJsLikeFile) tryParseTypeArguments() else null
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
                    // tsc parseDecoratedExpression: a non-class decorated expression is a
                    // MissingDeclaration — TS1109 "Expression expected." ZERO-WIDTH at the
                    // current token's FULL START (right after the decorator expression);
                    // the offending token is NOT consumed and the decorated garbage emits
                    // nothing (the recovered node is a zero-width empty identifier).
                    reportError("Expression expected.", code = 1109,
                        overrideStart = scanner.getPrevTokenEnd(), overrideLength = 0)
                    Identifier(text = "", pos = pos, end = pos)
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
                if (isIdentifier() || token == SyntaxKind.SuperKeyword) {
                    // (`super` IS a primary expression in tsc — parseSuperExpression; the
                    // identifier fallback keeps `new super(...)` parsing, superNewCall1.)
                    parseIdentifier()
                } else if (isKeyword()) {
                    // tsc parseIdentifier(Expression_expected): a RESERVED word in expression
                    // position yields a zero-width missing Identifier WITHOUT consuming —
                    // TS1109 at the token (same-start-deduped); the keyword re-parses in the
                    // enclosing context (`Number.NEGATIVE_INFINITY - \n var nan = …` keeps
                    // the var statement; `retVal += catch .Property` re-parses the catch).
                    reportError("Expression expected.", code = 1109)
                    Identifier(text = "", pos = pos, end = pos)
                } else if (token == SyntaxKind.Unknown) {
                    if (scanner.getTokenPos() == scanner.binaryMarkerTokenPos) {
                        // The binary-file marker token (U+FFFD to EOF, tsc's
                        // NonTextFileMarkerTrivia): source-elements recovery reports
                        // TS1128 spanning the WHOLE token (same-start-deduped away when
                        // the marker starts at offset 0, where TS1490 already sits).
                        // NOT consumed — parseStatements' safety mechanism skips it and
                        // discards the statement (no stray `;` in the JS emit).
                        reportError("Declaration or statement expected.", code = 1128)
                    } else {
                        // Unknown token = invalid character (e.g. `\` from an incomplete unicode escape).
                        // Report TS1127 "Invalid character." but do NOT consume the token —
                        // parseStatements' safety mechanism will skip it and discard this "statement".
                        // tsc's scanner-level invalid-character error spans the char — mirrored
                        // here for C0 control chars (binary garbage) only; `\`-style recovery
                        // keeps the legacy zero-width span.
                        val tokText = scanner.getTokenText()
                        val w = if (tokText.length == 1 && tokText[0].code < 0x20) 1 else 0
                        reportError("Invalid character.", code = 1127, overrideLength = w)
                    }
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
                        } else if (scanner.getTokenPos() == exprStatementStartPos && !exprStatementForced) {
                            // tsc: a statement STARTING with `=` (not `}`-preceded) is
                            // TS1128 "Declaration or statement expected." — the orphan
                            // `= [1, 2];` after an aborted binding pattern
                            // (reservedWords2 line 9). Nested `=` positions (a typeof/
                            // while-condition operand) and FORCED bodies keep TS1109.
                            reportError("Declaration or statement expected.", code = 1128)
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
                // tsc isParenthesizedArrowFunctionExpressionWorker: "() =>", "():"
                // AND "() {" are all definitely-arrow (the missing `=>` reports
                // "'=>' expected." and the block parses as the arrow body).
                scanner.getToken() == SyntaxKind.EqualsGreaterThan || scanner.getToken() == SyntaxKind.Colon ||
                        scanner.getToken() == SyntaxKind.OpenBrace
            } else {
                false
            }
        }
        if (parenArrowAllowed && isArrow) return parseArrowFunction(emptySet())

        // tsc isParenthesizedArrowFunctionExpressionWorker: "(identifier :" (or
        // "(this :") is DEFINITELY an arrow — a type-annotated parameter — even when
        // the '=>' never appears ("'=>' expected." recovery; the body becomes a
        // zero-width missing identifier and the tail re-parses in the enclosing
        // context, e.g. `const {x} = (a: any).props` → declarator `props`).
        val identColonArrow = scanner.lookAhead {
            scanner.scan() // skip (
            val first = scanner.getToken()
            if (!isIdentifierToken(first) && first != SyntaxKind.ThisKeyword) return@lookAhead false
            scanner.scan()
            scanner.getToken() == SyntaxKind.Colon
        }
        if (parenArrowAllowed && identColonArrow) return parseArrowFunction(emptySet())

        // tsc tristate: `(...` is DEFINITELY an arrow (rest parameter start) — even
        // with the `=>` missing (`var x4 = (...a: any[]) { }` → "'=>' expected.").
        val restArrow = scanner.lookAhead {
            scanner.scan()
            scanner.getToken() == SyntaxKind.DotDotDot
        }
        if (parenArrowAllowed && restArrow) return parseArrowFunction(emptySet())

        // tsc tristate FALSE: a first inner token that cannot START a parameter
        // (not identifier/this/'...'/binding-pattern/')') is definitely NOT an
        // arrow — `(1)=>` stays a parenthesized expression and the `=>` belongs
        // to the enclosing context. An identifier followed by anything other
        // than `,`/`=`/`)`/`?`/`:` (e.g. `(a + b)`) is also definitely-not.
        val tristateFalse = scanner.lookAhead {
            scanner.scan() // skip (
            val second = scanner.getToken()
            when {
                second == SyntaxKind.OpenBrace || second == SyntaxKind.OpenBracket -> false
                isIdentifierToken(second) || second == SyntaxKind.ThisKeyword -> {
                    val secondIsModifier = second == SyntaxKind.PublicKeyword ||
                            second == SyntaxKind.PrivateKeyword || second == SyntaxKind.ProtectedKeyword ||
                            second == SyntaxKind.ReadonlyKeyword || second == SyntaxKind.OverrideKeyword ||
                            second == SyntaxKind.DeclareKeyword || second == SyntaxKind.AbstractKeyword
                    scanner.scan()
                    val third = scanner.getToken()
                    when {
                        third == SyntaxKind.Comma || third == SyntaxKind.Equals ||
                                third == SyntaxKind.CloseParen || third == SyntaxKind.Question ||
                                third == SyntaxKind.Colon -> false
                        // tsc: a MODIFIER followed by an identifier (`(public x`,
                        // `(readonly x`) is Tristate.True — not definitely-false.
                        secondIsModifier && (isIdentifierToken(third) || third == SyntaxKind.ThisKeyword) &&
                                third != SyntaxKind.AsKeyword -> false
                        else -> true
                    }
                }
                else -> true
            }
        }

        // Try complex arrow detection: (params) => body  or  (params): RetType => body
        val maybeArrow = !tristateFalse && scanner.lookAhead {
            scanner.scan() // skip (
            var depth = 1
            // tsc: a PARAMETER cannot start with `(` — `(a, (b, c)) => x` and
            // `((a)) => x` are parenthesized comma expressions, not arrows
            // (fatarrowfunctionsOptionalArgsErrors2). Track the previous top-level
            // token: `(` right after the list opener or after a top-level comma
            // kills arrow-ness; `(` after `=`/`:`/etc. (defaults, type annotations)
            // is fine.
            var prev = SyntaxKind.OpenParen
            var invalidParamStart = false
            // B326: tracks whether the paren interior is a BARE identifier/comma list —
            // the only shape for which a '{' follow commits to an arrow (tsc validates
            // via a full speculative signature parse; `(y = z ==== 'fn') {` must stay a
            // parenthesized expression — parserUnparsedTokenCrash2).
            var onlyIdentsAndCommas = true
            while (depth > 0 && scanner.getToken() != SyntaxKind.EndOfFile) {
                val t = scanner.getToken()
                if (t == SyntaxKind.OpenParen) {
                    if (depth == 1 && (prev == SyntaxKind.OpenParen || prev == SyntaxKind.Comma)) {
                        invalidParamStart = true
                    }
                    depth++
                } else if (t == SyntaxKind.CloseParen) depth--
                if (depth >= 1 && t != SyntaxKind.CloseParen &&
                    !(depth == 1 && (isIdentifierToken(t) || t == SyntaxKind.Comma || t == SyntaxKind.OpenParen))) {
                    onlyIdentsAndCommas = false
                }
                prev = t
                if (depth > 0) scanner.scan()
            }
            if (invalidParamStart) return@lookAhead false
            if (depth == 0) {
                scanner.scan() // skip )
                when (scanner.getToken()) {
                    SyntaxKind.EqualsGreaterThan -> true
                    // B326 (tsc parseParenthesizedArrowFunctionExpression): a '{' right
                    // after the close paren commits to an arrow even without '=>' —
                    // "'=>' expected." and the block parses as the body (`(x) { }`) —
                    // but only when the interior is a bare identifier/comma list.
                    SyntaxKind.OpenBrace -> onlyIdentsAndCommas
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
        if (parenArrowAllowed && maybeArrow) return parseArrowFunction(emptySet())

        val pos = getPos()
        parseExpected(SyntaxKind.OpenParen)
        // tsc parseExpression → parsePrimaryExpression at a RESERVED keyword that cannot
        // start an expression: TS1109 'Expression expected.' + a ZERO-WIDTH missing inner
        // WITHOUT consuming — the ')' -expected below same-start-dedups and the keyword
        // re-parses in the enclosing context (reachabilityChecksNoCrash1's
        // `await (const v of asyncIterable)` → `await ()` + members `const: v`, `of`, …).
        val expr = if (isKeyword() && !isIdentifier() && !isStartOfExpressionKeyword()) {
            val mPos = scanner.getTokenPos()
            reportError("Expression expected.", code = 1109)
            Identifier(text = "", pos = mPos, end = mPos)
        } else parseExpression()
        // Capture same-line trailing comments between inner expression and ')' (e.g. `(a => 0 /*t3*/)`).
        val innerTrailing = scanner.getTrailingComments()
        // Capture comments on new lines before ')' (e.g. `//close`, `/*3*/` in multi-line paren).
        val beforeCloseParen = leadingComments()
        val closeParenFound = parseExpected(SyntaxKind.CloseParen)
        // Capture same-line trailing comments after ')' (e.g. `/*4*/` in `(expr)/*4*/`).
        val afterCloseParen = scanner.consumeTrailingComments()?.ifEmpty { null }
        val exprWithComments = if (!innerTrailing.isNullOrEmpty()) expr.withTrailingComments(innerTrailing) else expr
        return ParenthesizedExpression(
            expression = exprWithComments,
            beforeCloseParenComments = beforeCloseParen,
            afterCloseParenComments = afterCloseParen,
            closeParenMissing = !closeParenFound,
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
            // (GATE.2) A span, so a caret on `x` in `x => x + 1` descends to the
            // parameter rather than stopping at the arrow — see parseCatchClause.
            val paramPos = getPos()
            val paramName = parseIdentifier()
            listOf(Parameter(name = paramName, pos = paramPos, end = getEnd()))
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
        val lastTokenBeforeArrow = token
        parseExpected(SyntaxKind.EqualsGreaterThan)
        val savedAsync = inAsyncContext
        inAsyncContext = async
        functionLikeDepth++
        // tsc parseParenthesizedArrowFunctionExpression: the body parses only when
        // the token after the signature was '=>' or '{'; otherwise the body is a
        // ZERO-WIDTH missing identifier (the TS1003 would be same-start-deduped by
        // the "'=>' expected." just reported) and the orphan tail re-parses in the
        // enclosing context (e.g. the var-decl list's skip recovery).
        val body: Node = if (lastTokenBeforeArrow == SyntaxKind.EqualsGreaterThan ||
            lastTokenBeforeArrow == SyntaxKind.OpenBrace
        ) {
            if (token == SyntaxKind.OpenBrace) parseBlock()
            else if (token in ARROW_BODY_STATEMENT_KEYWORDS) {
                // B326 (tsc parseArrowFunctionExpressionBody): a plain STATEMENT start
                // (a keyword that cannot start an expression; function/class excluded)
                // after '=>' is a BLOCK with a missing '{' — "'{' expected." at the
                // keyword, then statements parse until '}' (IgnoreMissingOpenBrace;
                // the '}' is consumed as the block close): `() => var k = 10;}` →
                // `() => { var k = 10; }`.
                reportError("'{' expected.", code = 1005)
                val bPos = getPos()
                val stmts = parseStatements()
                val cbPos = if (token == SyntaxKind.CloseBrace) getPos() else -1
                parseExpected(SyntaxKind.CloseBrace)
                Block(statements = stmts, multiLine = false, pos = bPos, end = getEnd(), closeBracePos = cbPos)
            }
            else parseAssignmentExpression()
        } else {
            Identifier(text = "", pos = getPos(), end = getPos())
        }
        functionLikeDepth--
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
                // tsc parseDelimitedList recovery: a non-comma non-`]` follow token gets
                // ',' expected (same-start-deduped when colliding); an expression-start
                // token re-enters the list (`[name:string]` → elements name, string);
                // outer-owned closers/`;` abort; anything else is skipped one token.
                if (token != SyntaxKind.CloseBracket && token != SyntaxKind.EndOfFile) {
                    if (token == SyntaxKind.CloseParen || token == SyntaxKind.CloseBrace ||
                        token == SyntaxKind.Semicolon) break
                    reportError("',' expected.", code = 1005)
                    postCommaPerElem.add(null)
                    if (isStartOfExpression()) continue
                    reportError("Expression expected.", code = 1109)
                    nextToken()
                    continue
                }
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
            // tsc abortParsingListOrMoveToNextToken(ObjectLiteralMembers): a token that
            // cannot START a member (member starts per tsc isListElement: any literal
            // property name incl. keywords, `[`, `*`, `...`, `.`) → TS1136 (same-start-
            // deduped against a preceding ',' expected) + SKIP and continue — tsc skips
            // `)` AND `{` here (reachabilityChecksNoCrash1: the .types baseline shows all
            // six recovered members in ONE object literal spanning the derailed for-header).
            if (!isObjectLiteralMemberStartToken()) {
                reportError("Property assignment expected.", code = 1136,
                    overrideStart = getPos(), overrideLength = 1)
                // tsc abortParsingListOrMoveToNextToken → isInSomeParsingContext: ABORT
                // unconsumed when an ENCLOSING context claims the token — `=>` terminates a
                // variable-declarator list (parseErrorIncorrectReturnToken), a statement
                // start aborts via SourceElements. EXCEPTION: under the top-level-await
                // REPARSE (tsc reparseTopLevelAwait calls parseListElement directly, so the
                // SourceElements BIT IS NOT SET) statement starts do NOT abort — `)` and `{`
                // are SKIPPED (reachabilityChecksNoCrash1's .types baseline is the proof).
                if (token == SyntaxKind.EqualsGreaterThan ||
                    (!sawTopLevelAwaitExpr && canStartStatementForRecovery())) {
                    break
                }
                nextToken()
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
                if (token == SyntaxKind.CloseBrace || token == SyntaxKind.EndOfFile) break
                // tsc parseDelimitedList: no comma + not the terminator → "',' expected."
                // at the token; the loop re-enters (a member-start token parses the next
                // member; a non-member gets the TS1136+skip above).
                reportError("',' expected.", code = 1005)
                continue
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

        // tsc ObjectLiteralMembers isListElement includes Dot ("don't close the object") —
        // the member gets a MISSING zero-width name (TS1003 + ':' expected, both same-start-
        // deduped against the preceding ',' expected) and the VALUE parses from the dot
        // (`<missing>.push(await v)` — reachabilityChecksNoCrash1 line 4).
        if (token == SyntaxKind.Dot) {
            val missingPos = scanner.getTokenPos()
            reportError("Identifier expected.", code = 1003)
            reportError("':' expected.", code = 1005)
            val value = parseAssignmentExpression()
            return PropertyAssignment(
                name = Identifier(text = "", pos = missingPos, end = missingPos),
                initializer = value, pos = pos, end = getEnd(), leadingComments = comments,
            )
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

        // tsc records whether the name token was a (binding) identifier BEFORE parsing it —
        // reserved keywords can be property NAMES but never SHORTHAND members.
        val nameWasReservedKeyword = isKeyword() && !isIdentifier()
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

        // tsc parsePropertyAssignment: a property NAME followed by a same-line
        // LITERAL is a missing ':' — "':' expected." at the literal, which then
        // parses as the VALUE (`{return 0;}` recovers as `{ return: 0 }`; the
        // object-literal loop's `;`-recovery handles the separator).
        if (name is Identifier && !scanner.hasPrecedingLineBreak() &&
            (token == SyntaxKind.NumericLiteral || token == SyntaxKind.StringLiteral ||
                token == SyntaxKind.BigIntLiteral)
        ) {
            reportError("':' expected.", code = 1005,
                overrideLength = scanner.getTokenText().length.coerceAtLeast(1))
            val value = parseAssignmentExpression()
            return PropertyAssignment(name = name, initializer = value, modifiers = modifiers,
                pos = pos, end = getEnd(), leadingComments = comments)
        }

        // tsc parseObjectLiteralElement: SHORTHAND requires the name TOKEN to have been an
        // IDENTIFIER (incl. contextual keywords) — a RESERVED-keyword name (`const`, `for`)
        // takes parseExpected(Colon) → "':' expected." at the current token + parses the
        // VALUE anyway (reachabilityChecksNoCrash1's `{ const out = []; for await (…) }`).
        if (nameWasReservedKeyword && name is Identifier) {
            reportError("':' expected.", code = 1005)
            val value = parseAssignmentExpression()
            return PropertyAssignment(name = name, initializer = value, modifiers = modifiers,
                pos = pos, end = getEnd(), leadingComments = comments)
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
        functionLikeDepth++
        val body = parseBlock()
        functionLikeDepth--
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
        // `implements`/`extends` start heritage clauses ONLY when followed by an
        // identifier-or-keyword (tsc isImplementsClause); `class implements { }` takes
        // `implements` as the class NAME (the checker reports TS1213 on it).
        val heritageKeywordIsName = (token == SyntaxKind.ImplementsKeyword || token == SyntaxKind.ExtendsKeyword) &&
            !lookAhead { nextToken(); isIdentifier() || isKeyword() }
        val name = if (isIdentifier() && (heritageKeywordIsName ||
                (token != SyntaxKind.ImplementsKeyword && token != SyntaxKind.ExtendsKeyword))
        ) parseIdentifier() else null
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
            val savedPAA = parenArrowAllowed
            parenArrowAllowed = false
            val right = parseBinaryExpression(nextMinPrec)
            parenArrowAllowed = savedPAA
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
        // tsc doOutsideOfContext(disallowInAndDecoratorContext, parseArgumentOrArrayLiteralElement):
        // call arguments leave the decorator context, so `@dec(arr[0])` parses the inner
        // element access normally.
        val savedDecoratorContext = inDecoratorContext
        inDecoratorContext = false
        // A call argument leaves the heritage-base spine, so `extends foo(bar<T>)` parses the
        // inner instantiation expression normally (see parsingHeritageBase).
        val savedHeritageBase = parsingHeritageBase
        parsingHeritageBase = false
        try {
            return parseArgumentListWorker()
        } finally {
            inDecoratorContext = savedDecoratorContext
            parsingHeritageBase = savedHeritageBase
        }
    }

    private fun parseArgumentListWorker(): List<Expression> {
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
            // tsc abortParsingListOrMoveToNextToken(ArgumentExpressions): a RESERVED
            // keyword that cannot start an expression gets the context error TS1135
            // "Argument expression expected." at the token, then the list ABORTS
            // unconsumed when an enclosing context claims it (`Overloads( while : …)` —
            // `while` is a statement start, so the call becomes `Overloads()` and the
            // while re-parses as a statement; the `)`-expected dedups). Not claimed →
            // skip and continue.
            if (isKeyword() && !isIdentifier() && !isStartOfExpression()) {
                reportError("Argument expression expected.", code = 1135,
                    overrideStart = scanner.getTokenPos(),
                    overrideLength = scanner.getTokenText().length.coerceAtLeast(1))
                if (canStartStatementForRecovery()) break
                nextToken()
                continue
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
            if (!parseOptional(SyntaxKind.Comma)) {
                // tsc parseDelimitedList recovery: a same-line `=>` (B320) or `:` (B327)
                // after an argument reports ',' expected, is skipped (no context owns
                // it — the deduped TS1135 is omitted), and the list RE-ENTERS:
                // `foo((1)=>{return 0;})` → arguments `(1)` + the recovered object
                // literal; `test(name:string)` → arguments `name, string`.
                if ((token == SyntaxKind.EqualsGreaterThan || token == SyntaxKind.Colon) &&
                    !scanner.hasPrecedingLineBreak()) {
                    reportError("',' expected.", code = 1005,
                        overrideLength = if (token == SyntaxKind.EqualsGreaterThan) 2 else 1)
                    nextToken()
                    continue
                }
                // errorRecoveryInClassDeclaration: mirror tsc parseDelimitedList recovery for
                // ArgumentExpressions (isListElement = isStartOfExpression) — a non-comma
                // expression-start follow token reports ',' expected and RE-ENTERS the list
                // (`foo( public blaz() {} )` → arguments `public`, `blaz()`, `{}`). Abort on the
                // list/statement closers so we never run past the call. Conservative vs the
                // array-literal sibling (no `Expression expected` skip path).
                if (token != SyntaxKind.CloseParen && token != SyntaxKind.EndOfFile &&
                    token != SyntaxKind.CloseBrace && token != SyntaxKind.Semicolon &&
                    isStartOfExpression()) {
                    reportError("',' expected.", code = 1005)
                    continue
                }
                break
            }
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
        paramListAborted = false
        if (!parseExpected(SyntaxKind.OpenParen)) {
            // B324 (tsc parseParameters): a missing '(' yields an EMPTY missing list —
            // NOTHING is consumed; the caller's body parse and the statement-level
            // recovery see the raw tokens (`function ) {}` leaves ')' for the outer
            // context and the function gets a MISSING body).
            paramListAborted = true
            return emptyList()
        }
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
            // B324 (tsc parsingContextErrors for ParsingContext.Parameters): a RESERVED
            // keyword (one that cannot serve as a binding identifier) in parameter-name
            // position is NOT a parameter start — emit TS1390 and ABORT the list with the
            // token unconsumed (it can start a statement in the outer context; tsc
            // abortParsingListOrMoveToNextToken). The following ')'/'{'/';' expected
            // attempts dedup at the same start. MODIFIER-kind keywords (export/default/
            // const/in) ARE parameter starts per tsc isStartOfParameter→isModifierKind —
            // they take the parseParameter path (TS1090 'export' modifier cannot appear
            // on a parameter, etc.).
            if (isKeyword() && !isIdentifier() && token != SyntaxKind.ThisKeyword &&
                token != SyntaxKind.ExportKeyword && token != SyntaxKind.DefaultKeyword &&
                token != SyntaxKind.ConstKeyword && token != SyntaxKind.InKeyword &&
                !isStartOfType(token)) {
                // (B332: keywords that can START A TYPE — null/void/true/typeof/… — ARE
                // parameter starts per tsc isStartOfParameter→isStartOfType; they take
                // the parseParameter path below and get TS1359 + a missing name there.)
                val kwText = source.substring(scanner.getTokenPos(), scanner.getPos())
                reportError("'$kwText' is not allowed as a parameter name.", code = 1390)
                paramListAborted = true
                return params
            }
            // tsc abortParsingListOrMoveToNextToken (non-keyword case): a token that cannot
            // START a parameter (tsc isStartOfParameter incl. isStartOfType punctuation) →
            // TS1138 'Parameter declaration expected.' (same-start-deduped), then ABORT
            // unconsumed when the token can start an enclosing-context statement
            // (isInSomeParsingContext — incl. the binary-operator expression tolerance,
            // reachabilityChecksNoCrash1's `>`), else SKIP it and continue.
            if (!isStartOfParameterToken()) {
                reportError("Parameter declaration expected.", code = 1138, overrideLength = 1)
                if (canStartStatementForRecovery()) {
                    paramListAborted = true
                    break
                }
                nextToken()
                continue
            }
            val paramStartPos = scanner.getTokenPos()
            var param = parseParameter()
            // tsc parseDelimitedList no-progress guard + skip-recovery: a parameter that
            // consumed NOTHING (reserved word in name position — TS1359 + zero-width
            // missing name, e.g. `m(null: string)`) gets "',' expected." at the token
            // (same-start-deduped against the TS1359), the token is CONSUMED, and the
            // list re-enters; a follow token that can't start a parameter is TS1138
            // "Parameter declaration expected." + consumed (the `:`), so `string` becomes
            // the next parameter (reservedWords2 line 12: `m(, string)` in the emit).
            if (scanner.getTokenPos() == paramStartPos && token != SyntaxKind.CloseParen &&
                token != SyntaxKind.EndOfFile) {
                params.add(param)
                reportError("',' expected.", code = 1005, overrideLength = 1)
                nextToken()
                if (token == SyntaxKind.Colon || token == SyntaxKind.Equals) {
                    reportError("Parameter declaration expected.", code = 1138, overrideLength = 1)
                    nextToken()
                }
                continue
            }
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
                    isStartOfParameterToken()
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
                    // B18.2 checker-suppression marking stays confined to the ORIGINAL
                    // recovery shapes — a type-start punctuation re-entry (missing-name
                    // recovery) keeps its checker diagnostics (reachability's '(Missing)'
                    // TS7006/TS2300).
                    if (isIdentifier() || token == SyntaxKind.DotDotDot ||
                        token == SyntaxKind.OpenBrace || token == SyntaxKind.OpenBracket) {
                        nextParamFromCommaRecovery = true
                    }
                    continue
                }
                // tsc parseDelimitedList: no comma, not the terminator, not a parameter
                // start → parseExpected(Comma) reports ',' expected at the token, then the
                // LOOP TOP's abort logic (TS1138-deduped + abort-or-skip) decides
                // (reachabilityChecksNoCrash1's `>` after param `T`).
                if (token != SyntaxKind.CloseParen && token != SyntaxKind.EndOfFile) {
                    val recPos = scanner.getTokenPos()
                    val recLen = (scanner.getPos() - recPos).coerceAtLeast(1)
                    reportError("',' expected.", code = 1005,
                        overrideStart = recPos, overrideLength = recLen)
                    params.add(param)
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
        // tsc parseNameOfParameter: a RESERVED word in name position is TS1359 + a
        // zero-width missing name (not consumed) — except a MODIFIER-kind keyword
        // (export/default/const/in reach here) is then CONSUMED so the list makes
        // progress (`function f(default: number)` → one missing-named param with a
        // `number` annotation; tsc's comment cites `function foo(static)`).
        val name = if (token != SyntaxKind.OpenBrace && token != SyntaxKind.OpenBracket &&
            token != SyntaxKind.ThisKeyword && isKeyword() && !isIdentifier()) {
            val missing = parseDeclarationNameOrMissing()
            if (modifiers.isEmpty() && decorators.isNullOrEmpty() &&
                (token == SyntaxKind.ExportKeyword || token == SyntaxKind.DefaultKeyword ||
                    token == SyntaxKind.ConstKeyword || token == SyntaxKind.InKeyword)) {
                nextToken()
            }
            missing
        } else if (token != SyntaxKind.OpenBrace && token != SyntaxKind.OpenBracket &&
            token != SyntaxKind.ThisKeyword && !isIdentifier()) {
            // tsc parseNameOfParameter → createMissingNode: a NON-name token (`!`, `<`, …)
            // yields a ZERO-WIDTH missing name WITHOUT consuming (TS1003 same-start-deduped
            // against the preceding ',' expected); the list's zero-progress guard then
            // advances past the token (reachabilityChecksNoCrash1). NOTE: tsc does NOT
            // accept a definite-assignment `!` on a parameter — `asyncIterable!: T` derails.
            val missingPos = scanner.getTokenPos()
            reportError("Identifier expected.", code = 1003)
            Identifier(text = "", pos = missingPos, end = missingPos)
        } else parseBindingNameOrPattern()
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
        ).also { it.internSalt = typeParamFileSalt }
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

    /**
     * B419b: public entry for the Checker to parse a JSDoc `@type {T}` from a comment
     * list into a TypeNode (used to type a JS class expando `/** @type {number[]} */
     * this.p = []` member). The resulting node's positions point into the JSDoc text,
     * so the Checker must only use it to RESOLVE a member type, never to emit a
     * position-bearing diagnostic on the node itself.
     */
    fun parseJsDocTypeNodeFromComments(comments: List<Comment>?): TypeNode? =
        parsePropertyTypeFromJSDoc(comments)

    /** Like [parseJsDocTypeNodeFromComments] but for a BRACELESS `@type T` tag (T NOT wrapped in
     *  `{}`, e.g. a JSDoc comment whose body is `@type () => string`). Returns the parsed TypeNode,
     *  or null if the tag is absent / braced / unparseable. Positions point into the JSDoc text
     *  (offset 0) — RESOLVE only; never emit a position-bearing diagnostic on the returned node. */
    fun parseBracelessJsDocTypeFromComments(comments: List<Comment>?): TypeNode? {
        if (comments.isNullOrEmpty()) return null
        for (comment in comments) {
            if (comment.kind != SyntaxKind.MultiLineComment) continue
            val ct = comment.text
            if (!ct.startsWith("/**")) continue
            val typeText = extractAtTypeBracelessContent(ct) ?: continue
            return parseTypeFromText(typeText, fileName)
        }
        return null
    }

    /** Content of a BRACELESS `@type T` tag (everything after `@type ` to the comment close,
     *  single-line). Returns null when the tag is absent or the next non-space char is `{`
     *  (a braced `@type {T}` — owned by [extractAtTypeBraceContent]). */
    private fun extractAtTypeBracelessContent(commentText: String): String? {
        val typeIdx = commentText.indexOf("@type")
        if (typeIdx < 0) return null
        var i = typeIdx + 5
        while (i < commentText.length && (commentText[i] == ' ' || commentText[i] == '\t')) i++
        if (i >= commentText.length || commentText[i] == '{') return null
        var end = commentText.indexOf("*/", i)
        if (end < 0) end = commentText.length
        return commentText.substring(i, end).trim().ifEmpty { null }
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
    /** Result of [parseJSDocParamPrimitiveTypeMap]: synthesized param type nodes by name,
     *  plus the subset of names declared as a JSDoc rest param (`@param {...T} name`). */
    private data class JsDocParamTypeMap(val types: Map<String, TypeNode>, val restNames: Set<String>)

    private fun parseJSDocParamPrimitiveTypeMap(comments: List<Comment>?): JsDocParamTypeMap? {
        if (!isJsLikeFile || comments.isNullOrEmpty()) return null
        var map: MutableMap<String, TypeNode>? = null
        val restNames = mutableSetOf<String>()
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
                // B437: JSDoc rest param `@param {...PRIMITIVE} name` → a rest parameter
                // of type `PRIMITIVE[]`. Primitive element only (no name resolution → no
                // JSDoc-position risk). The element keyword node carries pos=-1/end=-1
                // (synthetic); the callers set `dotDotDotToken` for names in restNames.
                if (typeText.startsWith("...")) {
                    val elemText = typeText.substring(3).trim()
                    val elemKind = primitiveKeywordKindFor(elemText)
                    if (elemKind != null) {
                        if (map == null) map = mutableMapOf()
                        if (name !in map) {
                            map[name] = ArrayType(
                                elementType = KeywordTypeNode(kind = elemKind, pos = -1, end = -1),
                                pos = -1, end = -1,
                            )
                            restNames.add(name)
                        }
                    }
                    continue
                }
                val kind = primitiveKeywordKindFor(typeText)
                if (kind != null) {
                    if (map == null) map = mutableMapOf()
                    if (name !in map) {
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
                            if (name !in map) {
                                map[name] = typeRef
                            }
                        }
                    }
                }
            }
        }
        if (map == null) return null
        return JsDocParamTypeMap(map, restNames)
    }

    /** Apply JSDoc `@param {primitive} name` types to params whose `type` is null,
     *  matched by Identifier name. Non-Identifier param names (destructuring) are
     *  preserved unchanged. A `@param {...PRIMITIVE} name` (B437) also sets the
     *  param's `dotDotDotToken` so it becomes a typed rest parameter.
     *  Returns the original list when no JSDoc primitives match. */
    private fun applyJSDocParamPrimitiveTypes(
        params: List<Parameter>, comments: List<Comment>?,
    ): List<Parameter> {
        val parsed = parseJSDocParamPrimitiveTypeMap(comments) ?: return params
        val map = parsed.types
        var changed = false
        val out = params.map { p ->
            if (p.type != null) p
            else {
                val name = (p.name as? Identifier)?.text
                val t = name?.let { map[it] }
                if (t != null) {
                    changed = true
                    val isRest = name in parsed.restNames
                    p.copy(type = t, typeFromJSDoc = true, dotDotDotToken = p.dotDotDotToken || isRest)
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

    // Reverse of primitiveKeywordKindFor: the identifier text for a type-keyword
    // token. A type-predicate SUBJECT (`symbol is T`) is grammatically a parameter
    // NAME, but our parser reaches it via parseType, which turns a keyword-like name
    // (`symbol`/`string`/`object`/…) into a KeywordTypeNode instead of an Identifier.
    // Every legal keyword-named parameter is a contextual keyword, so mapping the
    // node back to its text recovers the true subject Identifier.
    private fun keywordTypeKindText(kind: SyntaxKind): String? = when (kind) {
        SyntaxKind.StringKeyword -> "string"
        SyntaxKind.NumberKeyword -> "number"
        SyntaxKind.BooleanKeyword -> "boolean"
        SyntaxKind.UnknownKeyword -> "unknown"
        SyntaxKind.NeverKeyword -> "never"
        SyntaxKind.UndefinedKeyword -> "undefined"
        SyntaxKind.BigIntKeyword -> "bigint"
        SyntaxKind.SymbolKeyword -> "symbol"
        SyntaxKind.ObjectKeyword -> "object"
        SyntaxKind.AnyKeyword -> "any"
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
        } catch (_: Exception) {
            null
        }
    }

    private fun runParseTypeFromExternal(): TypeNode? {
        return try {
            nextToken()
            val parsed = parseType()
            if (token != SyntaxKind.EndOfFile) null else parsed
        } catch (_: Exception) {
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
        // Assertion predicate: `asserts x [is T]` / `asserts this [is T]` (M1.5).
        // Builds a real TypePredicate(assertsModifier=true) — the parameter name is a
        // bare Identifier (`this` becomes Identifier("this"), matching the no-ThisExpression
        // convention). A bare `asserts` with no name keeps the old void-keyword recovery.
        if (token == SyntaxKind.AssertsKeyword) {
            nextToken()  // consume 'asserts'
            var paramName: Identifier? = null
            if (isIdentifier() || token == SyntaxKind.ThisKeyword) {
                val namePos = getPos()
                val nameText = if (token == SyntaxKind.ThisKeyword) "this" else scanner.getTokenValue()
                nextToken()  // consume the parameter/this name
                paramName = Identifier(text = nameText, pos = namePos, end = getEnd())
            }
            if (paramName != null) {
                var target: TypeNode? = null
                if (token == SyntaxKind.IsKeyword) {
                    nextToken()  // consume 'is'
                    target = parseType()
                }
                return TypePredicate(
                    parameterName = paramName,
                    type = target,
                    assertsModifier = true,
                    pos = pos,
                    end = getEnd(),
                )
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
        // and parse the actual predicate type. Round 423: the predicate target is the
        // WHOLE remaining type — `node is CallExpression | NewExpression` predicates
        // on the union (tsc parseTypePredicate → parseType). The old
        // parseIntersectionOrHigherType truncated the target at the first member and
        // the union-continuation below then wrapped the PREDICATE as a union member
        // (`(node is A) | B`), so a union-target guard silently never narrowed.
        if (token == SyntaxKind.IsKeyword) {
            // The type parsed so far is actually the parameter name. When the subject is a
            // keyword-like identifier (`symbol is T`, `string is T`, …) parseType produced a
            // KeywordTypeNode; recover the real subject Identifier so downstream predicate
            // consumers (narrowing, asserts) can match it to the parameter by name.
            val paramName: Node = (type as? KeywordTypeNode)
                ?.let { kw -> keywordTypeKindText(kw.kind)?.let { Identifier(text = it, pos = kw.pos, end = kw.end) } }
                ?: type
            nextToken()  // consume 'is'
            val predicateType = parseType()
            return TypePredicate(
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

    /** expressionWithJSDocTypeArguments: normalize a raw `<...>` instantiation type-arg list that
     *  contained JSDoc-`?` (nullable) markers. tsc re-prints each arg with all `?` collapsed to a
     *  single prefix: `?` → `?`, `string?` → `?string`, `?string?` → `??string`. */
    private fun normalizeJsDocTypeArgs(raw: String): String {
        val inner = raw.trim().removePrefix("<").removeSuffix(">")
        val parts = inner.split(",").map { part ->
            var s = part.trim()
            var q = 0
            while (s.startsWith("?")) { q++; s = s.substring(1).trim() }
            while (s.endsWith("?")) { q++; s = s.substring(0, s.length - 1).trim() }
            "?".repeat(q) + s
        }
        return "<" + parts.joinToString(", ") + ">"
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
                    sawJsDocInTypeArgs = true
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
            if (inTypeArgsDepth > 0) sawJsDocInTypeArgs = true
            // A direct tuple-element `?` (`[number?]`) is a valid optional marker, silently
            // consumed here; flag it so parseTupleType can record the optionality (gated to
            // NOT-inside-type-args so a `[Map<K, V?>]` inner `?` is not mistaken for the
            // element's marker).
            if (inTupleTypeDepth > 0 && inTypeArgsDepth == 0) tupleElementConsumedOptionalMarker = true
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
            if (inTypeArgsDepth > 0) sawJsDocInTypeArgs = true
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
    /** tsc isStartOfParameter (isJSDocParameter=false): rest/patterns/this/decorator/
     *  binding identifiers/modifier keywords + isStartOfType(inStartOfParameter=true) —
     *  which INCLUDES `!`/`?`/`*`/`<`/`|`/`&`/literals but EXCLUDES `(`/`-`/`function`.
     *  Keywords reaching this (after parseParameterList's keyword-abort branch filtered
     *  the non-starts) are all parameter starts. */
    /** Reserved keywords that CAN start an expression (tsc isStartOfLeftHandSideExpression/
     *  isStartOfExpression keyword cases). Contextual keywords pass isIdentifier() and never
     *  reach the callers' `isKeyword() && !isIdentifier()` gates. */
    private fun isStartOfExpressionKeyword(): Boolean = when (token) {
        SyntaxKind.ThisKeyword, SyntaxKind.SuperKeyword, SyntaxKind.NullKeyword,
        SyntaxKind.TrueKeyword, SyntaxKind.FalseKeyword, SyntaxKind.FunctionKeyword,
        SyntaxKind.ClassKeyword, SyntaxKind.NewKeyword, SyntaxKind.DeleteKeyword,
        SyntaxKind.TypeOfKeyword, SyntaxKind.VoidKeyword, SyntaxKind.AwaitKeyword,
        SyntaxKind.YieldKeyword, SyntaxKind.ImportKeyword -> true
        else -> false
    }

    /** tsc isListElement(ObjectLiteralMembers): `[` computed / `*` generator / `...` spread /
     *  `.` ("don't close the object") / any literal property name (identifier, keyword,
     *  string/numeric literal). */
    private fun isObjectLiteralMemberStartToken(): Boolean = when (token) {
        SyntaxKind.OpenBracket, SyntaxKind.Asterisk, SyntaxKind.DotDotDot, SyntaxKind.Dot,
        SyntaxKind.StringLiteral, SyntaxKind.NumericLiteral, SyntaxKind.BigIntLiteral -> true
        else -> isIdentifier() || isKeyword()
    }

    private fun isStartOfParameterToken(): Boolean = when (token) {
        SyntaxKind.DotDotDot, SyntaxKind.OpenBrace, SyntaxKind.OpenBracket, SyntaxKind.ThisKeyword,
        SyntaxKind.At,
        SyntaxKind.LessThan, SyntaxKind.Bar, SyntaxKind.Ampersand, SyntaxKind.Exclamation,
        SyntaxKind.Question, SyntaxKind.Asterisk, SyntaxKind.NewKeyword,
        SyntaxKind.StringLiteral, SyntaxKind.NumericLiteral, SyntaxKind.BigIntLiteral,
        SyntaxKind.NoSubstitutionTemplateLiteral, SyntaxKind.TemplateHead -> true
        else -> isIdentifier() || isKeyword()
    }

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
        // `abstract new (…) => T` (TS 4.2) — an ABSTRACT construct signature type.
        // tsc's `isStartOfFunctionTypeOrConstructorType` admits `abstract` here ONLY when
        // the very next token is `new`; its `parseModifiersForConstructorType` then consumes
        // the one modifier and falls into the ordinary constructor-type production.  The
        // lookahead is what keeps this additive: `abstract` is a plain identifier in type
        // position (`type X = abstract`, a type NAMED `abstract`), and every such spelling
        // still reaches the `else` arm below untouched.
        if (token == SyntaxKind.AbstractKeyword &&
            scanner.lookAhead { scanner.scan() == SyntaxKind.NewKeyword }
        ) {
            nextToken()  // consume 'abstract'; the node still starts at `pos`
            return parseConstructorType(modifiers = setOf(ModifierFlag.Abstract), startPos = pos)
        }
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
        // Per-element optionality: the `?` tokens below are discarded from the element
        // nodes, so record them here for `getTupleType` (an all-optional tuple target must
        // not count its elements as required — TS2739 on `[] : [a?, b?]`).
        val optional = mutableListOf<Boolean>()
        inTupleTypeDepth++
        try {
            while (token != SyntaxKind.CloseBracket && token != SyntaxKind.EndOfFile) {
                // Labeled tuple elements: `name: Type` or `name?: Type` or `...name: Type`
                val isRest = parseOptional(SyntaxKind.DotDotDot)
                var isOptional = false
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
                    if (parseOptional(SyntaxKind.Question)) isOptional = true // optional `?`
                    parseExpected(SyntaxKind.Colon) // consume `:`
                }
                tupleElementConsumedOptionalMarker = false
                val elementType = parseType()
                if (parseOptional(SyntaxKind.Question)) isOptional = true // optional tuple element: string?, number?
                // parseType's trailing-`?` recovery may have already consumed an unnamed
                // element's marker (`[number?]`); read+reset it here so a nested tuple's
                // marker does not leak to this element.
                if (tupleElementConsumedOptionalMarker) isOptional = true
                tupleElementConsumedOptionalMarker = false
                elements.add(if (isRest) RestType(type = elementType, pos = pos, end = getEnd()) else elementType)
                optional.add(isOptional)
                if (!parseOptional(SyntaxKind.Comma)) break
            }
        } finally {
            inTupleTypeDepth--
        }
        parseExpected(SyntaxKind.CloseBracket)
        return TupleType(
            elements = elements,
            elementOptional = if (optional.any { it }) optional else null,
            pos = pos, end = getEnd(),
        )
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
        var readonlyMinus = false
        if (token == SyntaxKind.Plus || token == SyntaxKind.Minus) {
            // +readonly / -readonly: consume modifier sign, then readonly.
            // M1.10: the MINUS form STRIPS readonly (the `Mutable<T>` idiom) —
            // record it so the checker's mapped-member synthesis can mark the
            // members writable.
            val isMinus = token == SyntaxKind.Minus
            nextToken()
            if (token == SyntaxKind.ReadonlyKeyword ||
                (isIdentifier() && scanner.getTokenValue() == "readonly")
            ) {
                readonlyToken = true
                readonlyMinus = isMinus
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
        // Round 718: `-?` REMOVES optionality (`Required<T>`) while `?`/`+?` ADD it.
        // Recording both as a bare `questionToken` made Required<T> behave as Partial<T>
        // — inverted — so the minus form gets its own flag, mirroring readonlyMinus.
        var questionMinus = false
        when (token) {
            SyntaxKind.Question -> { questionToken = true; nextToken() }
            SyntaxKind.Plus, SyntaxKind.Minus -> {
                val isMinus = token == SyntaxKind.Minus
                nextToken()
                if (token == SyntaxKind.Question) {
                    questionToken = true; questionMinus = isMinus; nextToken()
                }
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
            readonlyMinus = readonlyMinus,
            questionMinus = questionMinus,
            pos = pos,
            end = getEnd(),
        )
    }

    /**
     * `new (…) => T`, and since the `abstract` arm of [parsePrimaryType] also
     * `abstract new (…) => T`.
     *
     * [startPos] exists because an `abstract` prefix has already been consumed by the time
     * this runs, and the node must still span from that keyword — `getPos()` here would
     * report the `new`.  -1 (the default) means "start where I am".
     */
    private fun parseConstructorType(
        modifiers: Set<ModifierFlag> = emptySet(),
        startPos: Int = -1,
    ): TypeNode {
        val pos = if (startPos >= 0) startPos else getPos()
        nextToken() // skip 'new'
        val typeParams = parseTypeParametersOpt()
        val params = parseParameterList()
        parseExpected(SyntaxKind.EqualsGreaterThan)
        val type = parseType()
        return ConstructorType(
            typeParameters = typeParams, parameters = params, type = type,
            modifiers = modifiers, pos = pos, end = getEnd(),
        )
    }

    private fun parseImportType(): TypeNode {
        val pos = getPos()
        val isTypeOf = false
        nextToken() // skip 'import'
        parseExpected(SyntaxKind.OpenParen)
        val arg = parseType()
        // B397/round 369: optional import attributes clause `, { with|assert: { <attrs> } }`.
        // tsc `parseImportType` parses this as a structured ImportAttributes node (NOT a lenient
        // object-literal expression), so a malformed inner attribute list (`{1234, …}` — a
        // numeric literal is not an attribute name) ABORTS the list WITHOUT consuming, and the
        // leftover derails into statements exactly like tsc (parseImportAttributesError /
        // parseAssertEntriesError). The prior `parseAssignmentExpression()` swallowed the whole
        // `{ with: {1234,…} }` as an object literal so it never derailed.
        if (parseOptional(SyntaxKind.Comma)) {
            parseExpected(SyntaxKind.OpenBrace) // `{` of `{ with: {...} }`
            val isAssert = token == SyntaxKind.Identifier && scanner.getTokenValue() == "assert"
            val isWith = token == SyntaxKind.WithKeyword
            if (isWith || isAssert) {
                if (isAssert) {
                    // `assert` is deprecated — TS2880 at the inner attributes object (`{`),
                    // matching the typePosition span (initializer.pos, len 1).
                    val innerBracePos = scanner.lookAhead {
                        scanner.scan(); scanner.scan() // over `assert` and `:`
                        scanner.getTokenPos()
                    }
                    reportError(
                        "Import assertions have been replaced by import attributes. Use 'with' instead of 'assert'.",
                        code = 2880, overrideStart = innerBracePos, overrideLength = 1,
                    )
                }
                nextToken() // consume `with`/`assert`
            } else {
                reportError("'with' expected.", code = 1005)
            }
            parseExpected(SyntaxKind.Colon)
            parseImportTypeAttributeList() // `{ <attrs> }` — aborts at a non-attribute token
            parseOptional(SyntaxKind.Comma)
            parseExpected(SyntaxKind.CloseBrace) // `}` of `{ with: {...} }`
        }
        parseExpected(SyntaxKind.CloseParen)
        recordModuleSpecifier((arg as? LiteralType)?.literal)
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

    // tsc `parseImportAttributes` inner list (`{ <attr>, <attr> }`) for an import TYPE. Each
    // attribute is `name : value` where name is an identifier/keyword/string. A non-attribute
    // token that can start an enclosing statement (`1234`) ABORTS the list WITHOUT consuming
    // (parseDelimitedList recovery), so the leftover re-parses as statements.
    private fun parseImportTypeAttributeList() {
        if (parseExpected(SyntaxKind.OpenBrace)) {
            while (true) {
                if (isIdentifier() || isKeyword() || token == SyntaxKind.StringLiteral) {
                    // parseImportAttribute: name : value
                    if (token == SyntaxKind.StringLiteral) parseStringLiteral() else parseIdentifierName()
                    parseExpected(SyntaxKind.Colon)
                    parseAssignmentExpression()
                    if (parseOptional(SyntaxKind.Comma)) continue
                    if (token == SyntaxKind.CloseBrace) break
                    parseExpected(SyntaxKind.Comma)
                    continue
                }
                if (token == SyntaxKind.CloseBrace) break
                reportError("Identifier expected.", code = 1003)
                if (token == SyntaxKind.CloseBrace || canStartStatementForRecovery()) break
                nextToken()
            }
            parseExpected(SyntaxKind.CloseBrace)
        }
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
            // A trailing/missing right side after `.` (e.g. `import x = A.B.<EOF>`) is a syntax
            // error → TS1003 "Identifier expected." at the position right after the dot (len 0),
            // mirroring tsc. parseIdentifierName otherwise silently produces an empty Identifier.
            if (!isIdentifier() && !isKeyword()) {
                val afterDotPos = scanner.getPrevTokenEnd()
                reportError("Identifier expected.", code = 1003, overrideStart = afterDotPos, overrideLength = 0)
                val missing = Identifier(text = "", pos = afterDotPos, end = afterDotPos)
                name = QualifiedName(left = name, right = missing, pos = name.pos, end = afterDotPos)
                break
            }
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
     * tsc `parseModuleExportName` → `parseNameWithKeywordCheck` → `parseIdentifierName`, but a
     * token that is NOT a valid module-export name (identifier / keyword / string literal — e.g.
     * a numeric or bigint literal) yields a zero-width missing Identifier WITHOUT consuming the
     * token, so the enclosing named-imports/exports list aborts on it and the offending token
     * re-parses as its own statement (bigintArbirtraryIdentifier: `import { 0n as foo }` /
     * `export { 0n as foo }`). A plain `parseIdentifierName` always advances, wrongly swallowing
     * `0n` into a (nearly-valid, then erased) specifier.
     */
    private fun parseModuleExportNameOrMissing(): Identifier {
        if (!(isIdentifier() || isKeyword() || token == SyntaxKind.StringLiteral)) {
            val p = getPos()
            reportError("Identifier expected.", code = 1003)
            return Identifier(text = "", pos = p, end = p)
        }
        return parseIdentifierName()
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

    // -----------------------------------------------------------------------
    // `using` / `await using` declaration heads (TS 5.2, explicit resource management)
    //
    // `using` is a CONTEXTUAL keyword: `const using = 1; using + 1;` and `using.foo()`
    // and `{ using: 1 }` must keep meaning what they meant.  What separates a
    // declaration head from an ordinary identifier is tsc's LOOKAHEAD
    // (`isUsingDeclaration` / `isAwaitUsingDeclaration`, parser.ts) — a binding
    // identifier or a `{` binding pattern on the SAME LINE.  An `[` is deliberately NOT
    // a start: `using[x]` is an element access.  ASI is why the same-line test is
    // load-bearing (`using\nx` is two expression statements).
    // -----------------------------------------------------------------------

    /** tsc `nextTokenIsBindingIdentifierOrStartOfDestructuringOnSameLine`. Advances. */
    private fun nextTokenIsBindingIdentifierOrStartOfDestructuringOnSameLine(
        disallowOf: Boolean,
    ): Boolean {
        nextToken()
        // In a for-head `of` is the loop operator, so `for (using of x)` iterates the
        // VALUE `using` — unless what follows makes `of` the declarator NAME.
        if (disallowOf && token == SyntaxKind.OfKeyword) {
            return lookAhead {
                nextToken()
                token == SyntaxKind.Equals || token == SyntaxKind.Semicolon ||
                    token == SyntaxKind.Colon
            }
        }
        return (isIdentifier() || token == SyntaxKind.OpenBrace) && !scanner.hasPrecedingLineBreak()
    }

    /** tsc `isUsingDeclaration` — the current token is `using`. */
    private fun isUsingDeclaration(disallowOf: Boolean = false): Boolean =
        lookAhead { nextTokenIsBindingIdentifierOrStartOfDestructuringOnSameLine(disallowOf) }

    /** tsc `isAwaitUsingDeclaration` — the current token is `await`. */
    private fun isAwaitUsingDeclaration(disallowOf: Boolean = false): Boolean = lookAhead {
        if (nextToken() == SyntaxKind.UsingKeyword) {
            nextTokenIsBindingIdentifierOrStartOfDestructuringOnSameLine(disallowOf)
        } else false
    }

    /** tsc createIdentifier for declaration-name positions: an identifier-capable token
     *  parses normally; a RESERVED keyword reports TS1359 "Identifier expected. '{0}' is
     *  a reserved word that cannot be used here." at the token WITHOUT consuming it and
     *  yields a ZERO-WIDTH missing Identifier at the PREVIOUS token's end (tsc
     *  createMissingNode at getNodePos() — `function throw()`'s missing name sits on the
     *  space after `function`); anything else falls back to [parseIdentifier]'s TS1003
     *  path. The follow-up '{'/'('-expected at the keyword is same-start-deduped. */
    private fun parseDeclarationNameOrMissing(): Identifier {
        if (isIdentifier()) return parseIdentifier()
        if (isKeyword()) {
            reportError(
                "Identifier expected. '${scanner.getTokenValue()}' is a reserved word that cannot be used here.",
                code = 1359,
                overrideStart = scanner.getTokenPos(),
                overrideLength = scanner.getTokenText().length)
            val p = scanner.getPrevTokenEnd()
            return Identifier(text = "", pos = p, end = p)
        }
        return parseIdentifier()
    }

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
        TryKeyword -> "try"
        CatchKeyword -> "catch"
        FinallyKeyword -> "finally"
        else -> kind.name
    }

}

/**
 * B326 (tsc parseArrowFunctionExpressionBody gate): statement-START keywords that cannot
 * start an EXPRESSION — an arrow body beginning with one of these is a block with a
 * missing '{'. `function`/`class` are deliberately excluded (expression starts), as are
 * semicolons (tsc's explicit exclusions).
 */
private val ARROW_BODY_STATEMENT_KEYWORDS = setOf(
    SyntaxKind.VarKeyword, SyntaxKind.ConstKeyword,
    SyntaxKind.IfKeyword, SyntaxKind.WhileKeyword, SyntaxKind.DoKeyword, SyntaxKind.ForKeyword,
    SyntaxKind.ReturnKeyword, SyntaxKind.SwitchKeyword, SyntaxKind.ThrowKeyword,
    SyntaxKind.TryKeyword, SyntaxKind.BreakKeyword, SyntaxKind.ContinueKeyword,
    SyntaxKind.DebuggerKeyword, SyntaxKind.WithKeyword,
)

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

// `computeLineStarts` used to live here, as the Parser's private line index. It is
// now `internal` in LineStarts.kt, beside the one statement of the line-terminator
// convention it implements, because the Checker and the tsconfig reader each had
// their OWN offset-to-line loop and one of them disagreed with this one about a
// lone `\r` (round 915).
