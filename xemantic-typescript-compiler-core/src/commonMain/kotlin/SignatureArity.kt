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

import com.xemantic.typescript.compiler.Checker.FuncParamInfo

/**
 * (INV.0) (P18.232) — the (CHK.175)/(CHK.176)(a) SIGNATURE-BASED ARITY reader: tsgo's
 * `hasCorrectArity` for a spread-free argument list ([callArityFails]) and its
 * `getArgumentArityError` over the signatures a call resolved to (TS2554 / TS2555 / TS2575,
 * [reportSignatureArity]; TS2556 for a surviving spread, [reportSpreadSignatureArity]), with
 * the declaration-trust, identifier-callee-binding and overload-completeness guards and the
 * span dedup against the name-based arity walkers. Extracted VERBATIM from `Checker.kt` (one
 * contiguous span, 170417-170762); every Checker member it reads is reached through
 * [checker].
 *
 * (P18.237) The rest of the ARITY family followed it, VERBATIM, from five more spans: the
 * arity message and emitters (`formatExpectedArgs`, `emitTS2554TooMany` / `TooFew`,
 * `emitTS2555TooFew`), the spread-arity view (`fixedTupleLengthOfRestParam`,
 * `argumentExpansionCount`, [SpreadArityView], `spreadArityView`, `spreadArityFails`,
 * `firstExcessArgIndex`, `emitTS2556` and the spread-operand classifier),
 * `signatureDeclaredArity`, and the call-side minimum (`callMinArgumentCount` x2,
 * `overloadCallMin`, `typeAcceptsVoid`). Their callers that stay on [Checker] — the
 * name-based walkers, the property-access reader and `relationMinArgumentCount` in the
 * relation — call this collaborator directly. Ambient reads:
 * `docs/inversion-ambient-ledger.md` rows 14 and 15.
 */
internal class SignatureArity(
    private val checker: Checker,
    private val options: CompilerOptions,
) {

    /**
     * (CHK.175) tsgo's `hasCorrectArity` for a SPREAD-FREE argument list: true only when
     * [sig] certainly rejects `args.size`, so its arguments must not be related (tsgo reports
     * the arity error alone — no TS2345, and no TS2769 when every overload fails arity).
     *
     * Conservative in the one direction that costs a missing row: `false` whenever the
     * count is not decidable here — a spread (TS2556's family), no declaration (a combined
     * union signature, whose reader decides arity itself), a JS declaration (JSDoc `[p]`
     * optionality is not in [paramInfo]'s TS reading), or an EMBEDDED-lib member (it
     * simplifies optional parameters away, B279). Too few arguments fail only when some
     * missing position is not void-accepting — tsgo's `acceptsVoid` loop, so `g(1)` for
     * `g(s: string, v: void)` still reports its TS2345.
     */
    fun callArityFails(args: List<Expression>, sig: Signature): Boolean {
        val n = args.size
        // Fast path for the ordinary call: within the symbol-level range the declared range
        // can only be wider (a binding pattern is dropped from `parameters` but counted in
        // `minArgumentCount`), so the answer is `false` either way — except for a rest
        // parameter typed as a fixed TUPLE, which tsgo expands into required positions.
        if (n >= sig.minArgumentCount && n <= sig.parameters.size && !endsInAnyTupleRest(sig)) return false
        if (args.any { it is SpreadElement }) return false
        val info = signatureDeclaredArity(sig) ?: return false
        if (!arityDeclTrusted(sig)) return false
        val a = callArity(info, sig, n)
        return a.fails(n)
    }

    /** (CHK.176)(a) Is [sig]'s declaration one whose arity this checker reads exactly — not
     *  JS (JSDoc `[p]` optionality is not in [paramInfo]'s reading), not an EMBEDDED-lib
     *  member (it simplifies optional parameters away, B279), and present at all. */
    private fun arityDeclTrusted(sig: Signature): Boolean {
        val decl = sig.declaration ?: return false
        if (!options.useRealLibs && (decl in checker.builtinLibDecls || decl in checker.builtinLibMemberDecls)) return false
        var root: Node = decl
        while (true) root = (root as? NodeBase)?.parent ?: break
        return (root as? SourceFile)?.fileName?.let { checker.isJsLikeFileName(it) } == false
    }

    /** (CHK.176)(a) Does [sig] end in a rest parameter annotated with a fixed tuple? */
    private fun endsInTupleRest(sig: Signature): Boolean {
        val p = sig.parameters.lastOrNull()?.valueDeclaration as? Parameter ?: return false
        return p.dotDotDotToken && fixedTupleLengthOfRestParam(p) != null
    }

    /** (CHK.181) Does [sig] end in a rest parameter annotated with ANY tuple literal — a
     *  fixed one or one carrying a rest element (`...a: [string, ...number[]]`), whose
     *  leading required elements are required call arguments all the same. */
    private fun endsInAnyTupleRest(sig: Signature): Boolean {
        val p = sig.parameters.lastOrNull()?.valueDeclaration as? Parameter ?: return false
        return p.dotDotDotToken && restTupleOf(p) != null
    }

    /** (CHK.176)(a) tsgo's call arity of one signature: `getMinArgumentCount` (void-trimmed),
     *  `getParameterCount` and `hasEffectiveRestParameter`, with a fixed-tuple rest expanded
     *  into its elements (`...a: [string, number?]` is one required and one optional). */
    private class CallArity(val min: Int, val max: Int, val hasRest: Boolean) {
        fun fails(n: Int) = (!hasRest && n > max) || n < min
    }

    private fun callArity(info: FuncParamInfo, sig: Signature?, trimBelow: Int): CallArity {
        tupleRestArity(info, sig, trimBelow)?.let { return it }
        // The void trim resolves parameter types — only below the declared minimum
        // ([trimBelow] = the argument count, or -1 for the reported range).
        val min = if (trimBelow < info.minParams) callMinArgumentCount(info, sig) else info.minParams
        return CallArity(min, info.maxParams, info.hasRest)
    }

    /**
     * (CHK.176)(a) / (CHK.181) The call arity of a signature whose rest parameter is
     * annotated with a tuple LITERAL, or null for any other signature: tsgo expands the tuple
     * into positions (`getParameterCount` / `getMinArgumentCount` over the tuple's
     * `fixedLength` / `minLength`). The minimum counts the REQUIRED elements before the
     * first rest element (the parser records a `?` in [TupleType.elementOptional]) — a
     * trailing element AFTER a rest element counts for nothing (measured: tsgo reads
     * `[string, ...number[], boolean]` as `at least 1`); the maximum is the fixed prefix,
     * unbounded ([CallArity.hasRest]) when the tuple has a rest element of its own.
     */
    private fun tupleRestArity(info: FuncParamInfo, sig: Signature?, trimBelow: Int): CallArity? {
        if (!info.hasRest) return null
        val tuple = restTupleOf(info.parameters.lastOrNull { it.dotDotDotToken }) ?: return null
        val restAt = tuple.elements.indexOfFirst { it is RestType }
        val head = if (restAt < 0) tuple.elements.size else restAt
        val opt = tuple.elementOptional
        val required = (0 until head).count { opt?.getOrNull(it) != true }
        val min = if (required > 0) info.maxParams + required
            else if (trimBelow < info.minParams) callMinArgumentCount(info, sig) else info.minParams
        return CallArity(min, info.maxParams + head, hasRest = restAt >= 0)
    }

    /** (CHK.181) [tupleRestArity] for the name-based arity walker: the reported range
     *  (`min`, `max`, `hasRest`) of a callee whose rest parameter is a tuple literal, or
     *  null. Same numbers the signature reader reports, so the two agree by construction. */
    internal fun tupleRestCallRange(info: FuncParamInfo): Triple<Int, Int, Boolean>? =
        tupleRestArity(info, null, trimBelow = -1)?.let { Triple(it.min, it.max, it.hasRest) }

    /**
     * (CHK.176)(a) tsgo's `getArgumentArityError` for a call every one of whose [sigs] fails
     * arity ([callArityFails]) — the ONE signature-based TS2554 / TS2555 / TS2575 reader, for
     * the calls the name-based walkers ([spineArgCallEnter], `new`, property-access,
     * overload) never reach: a function-typed parameter or variable, a real-lib function or
     * member, an overloaded constructor, an optional chain, a tuple rest, a call result.
     *
     * Rows (checker.go `getArgumentArityError`): too MANY squiggles `args[max]..args.last`;
     * too FEW anchors at `getErrorNodeForCallNode` — the callee (its NAME for a property
     * access), the whole expression for `new` — with the closest signature's missing
     * parameter as TS6210; a count strictly between the overloads' extremes that no overload
     * takes is TS2575. The range is `min-max` across the set, `at least min` (TS2555) when
     * any signature has a rest parameter.
     *
     * Deduplicated against every other arity emitter by SPAN: the name walker runs at the
     * call's enter and the property-access / method-overload readers just before this one,
     * all drawing tsgo's span through the same emit helpers, so a row already present over
     * the same span in the same file (any of TS2554/2555/2556/2575) means the call is
     * reported. Emits nothing unless
     * [arityCall]'s argument list is [args] itself.
     */
    fun reportSignatureArity(args: List<Expression>, sigs: List<Signature>, source: String, fileName: String) {
        val call = checker.arityCall ?: return
        val callArgs = when (call) {
            is CallExpression -> call.arguments
            is NewExpression -> call.arguments
            else -> null
        }
        if (callArgs !== args || sigs.isEmpty()) return
        val calleeId = when (call) {
            is CallExpression -> call.expression
            is NewExpression -> call.expression
            else -> null
        } as? Identifier
        if (calleeId != null && sigs.any { !arityIdentifierCalleeTrusted(calleeId, it) }) return
        if (!arityOverloadSetComplete(sigs)) return
        val n = args.size
        var minCount = Int.MAX_VALUE
        var maxCount = Int.MIN_VALUE
        var maxBelow = Int.MIN_VALUE
        var minAbove = Int.MAX_VALUE
        var hasRest = false
        var closest: FuncParamInfo? = null
        var closestSig: Signature? = null
        for (sig in sigs) {
            val info = signatureDeclaredArity(sig) ?: return
            if (!arityDeclTrusted(sig)) return
            val a = callArity(info, sig, trimBelow = -1)
            if (!a.fails(n)) return
            if (a.min < minCount) { minCount = a.min; closest = info; closestSig = sig }
            maxCount = maxOf(maxCount, a.max)
            if (a.min < n && a.min > maxBelow) maxBelow = a.min
            if (n < a.max && a.max < minAbove) minAbove = a.max
            if (a.hasRest) hasRest = true
        }
        val errorNode: Expression = when (call) {
            is CallExpression -> (call.expression as? PropertyAccessExpression)?.name ?: call.expression
            else -> call
        }
        when {
            minCount < n && n < maxCount -> {
                val length = checker.expressionTrueEnd(errorNode) - errorNode.pos
                if (length <= 0 || arityRowAt(fileName, errorNode.pos, length)) return
                val (line, ch) = checker.getLineAndCharacterOfPosition(source, errorNode.pos)
                checker.diagnostics.add(Diagnostic(
                    message = "No overload expects $n arguments, but overloads do exist that expect either $maxBelow or $minAbove arguments.",
                    category = DiagnosticCategory.Error, code = 2575,
                    fileName = fileName, line = line, character = ch,
                    start = errorNode.pos, length = length,
                ))
            }
            n < minCount -> {
                if (arityRowAt(fileName, errorNode.pos, checker.expressionTrueEnd(errorNode) - errorNode.pos)) return
                // The missing parameter's TS6210 lives in the DECLARING file.
                var root: Node? = closestSig?.declaration
                while (root != null && root !is SourceFile) root = (root as NodeBase).parent
                val declFile = root
                val params = closest?.parameters.orEmpty()
                if (hasRest) {
                    emitTS2555TooFew(
                        minCount, n, errorNode, source, fileName,
                        if (declFile == null || declFile.fileName == fileName) params else emptyList(),
                    )
                } else {
                    emitTS2554TooFew(
                        minCount, maxCount, n, errorNode, source, fileName, params,
                        declFile?.text, declFile?.fileName,
                    )
                }
            }
            n > maxCount && maxCount >= 0 -> {
                // emitTS2554TooMany's own span, so a row the name walker drew matches.
                if (arityRowAt(fileName, args[maxCount].pos, args.last().end - 1 - args[maxCount].pos)) return
                emitTS2554TooMany(minCount, maxCount, n, args, maxCount, source, fileName)
            }
        }
    }

    /** (CHK.176)(a) TS2556 for a single signature whose argument list carries a SURVIVING
     *  spread it cannot take ([spreadArityView] / [spreadArityFails], tsgo's first line of
     *  `getArgumentArityError`); true when the call is consumed (reported, here or earlier). */
    fun reportSpreadSignatureArity(args: List<Expression>, sig: Signature, source: String, fileName: String): Boolean {
        if (args.none { it is SpreadElement }) return false
        val call = checker.arityCall ?: return false
        val callArgs = when (call) {
            is CallExpression -> call.arguments
            is NewExpression -> call.arguments
            else -> null
        }
        if (callArgs !== args) return false
        val calleeId = when (call) {
            is CallExpression -> call.expression
            is NewExpression -> call.expression
            else -> null
        } as? Identifier
        if (calleeId != null && !arityIdentifierCalleeTrusted(calleeId, sig)) return false
        if (!arityOverloadSetComplete(listOf(sig))) return false
        val view = spreadArityView(args) as? SpreadArityView.Spread ?: return false
        val info = signatureDeclaredArity(sig) ?: return false
        if (!arityDeclTrusted(sig) || endsInTupleRest(sig)) return false
        if (!spreadArityFails(view, info.minParams, info.maxParams, info.hasRest)) return false
        val length = (checker.expressionTrueEnd(view.arg.expression) - view.arg.pos).coerceAtLeast(1)
        if (!arityRowAt(fileName, view.arg.pos, length)) emitTS2556(view.arg, source, fileName)
        return true
    }

    /**
     * (CHK.176)(a) For a call whose callee is a bare IDENTIFIER: does the name's nearest
     * lexical binding own [sig]? The callee's type comes from the checker's scope tables,
     * which cannot see every binding — an `any`-annotated parameter is in no walk-scoped
     * table, a function expression's own name and a destructured parameter neither — so
     * `function g(f: any) { f() }` beside an outer `function f(a, b)` resolves `f` to the
     * OUTER function. Every argument reader was blind to that (zero arguments relate
     * nothing); an ARITY reader turns it into a false TS2554 on legal code, so it reports
     * only when the syntax agrees with the resolution.
     *
     * The binding is found by walking the callee's ancestors (parameters, a function or
     * class expression's own name, statement-list declarations and imports, loop and
     * `catch` variables). It owns [sig] when: it is an import; a function declaration in
     * the same list as [sig]'s (an overload set); a class and [sig] is a constructor or a
     * class; a variable, parameter or binding element that CONTAINS [sig]'s declaration,
     * or — for a variable or parameter — is annotated with a type other than `any` /
     * `unknown` (`declare const f: F` reaches `F`'s call signature through a reference).
     * No binding in the file means a global: trusted, unless two function declarations
     * implement the name (TS2393 — tsgo then reads them as overloads).
     */
    private fun arityIdentifierCalleeTrusted(callee: Identifier, sig: Signature): Boolean {
        val name = callee.text
        val decl = sig.declaration ?: return false
        var cur: Node? = (callee as NodeBase).parent
        while (cur != null) {
            val b = arityBindingIn(cur, name)
            if (b != null) return arityBindingOwns(b, decl, name)
            cur = (cur as NodeBase).parent
        }
        val sym = checker.globals[name] ?: return true
        return sym.declarations.count { it is FunctionDeclaration && it.body != null } <= 1
    }

    /**
     * (CHK.176)(a) Do [sigs] carry every overload their declarations spell? A callee type
     * reached through some aliases keeps ONE signature of an overload set (measured on tsc's
     * own `src/harness/vpathUtil.ts`: `export import extname = ts.getAnyExtensionFromPath`,
     * three overloads, typed as the first alone), and the arity of one overload is not the
     * call's. Counts the body-less function / method declarations of the same name beside
     * each signature's declaration; fewer signatures than that is an incomplete set.
     */
    private fun arityOverloadSetComplete(sigs: List<Signature>): Boolean {
        for (sig in sigs) {
            val decl = sig.declaration
            val name = when (decl) {
                is FunctionDeclaration -> decl.name?.text
                is MethodDeclaration -> (decl.name as? Identifier)?.text
                else -> null
            } ?: continue
            val siblings: List<Node> = when (val p = (decl as NodeBase).parent) {
                is SourceFile -> p.statements
                is Block -> p.statements
                is ModuleBlock -> p.statements
                is ClassDeclaration -> p.members
                is ClassExpression -> p.members
                is InterfaceDeclaration -> p.members
                is TypeLiteral -> p.members
                else -> continue
            }
            val overloads = siblings.count {
                (it is FunctionDeclaration && it.body == null && it.name?.text == name) ||
                    (it is MethodDeclaration && it.body == null && (it.name as? Identifier)?.text == name)
            }
            if (overloads > sigs.size) return false
        }
        return true
    }

    /** (CHK.176)(a) The declaration in scope node [scope] that binds [name], or null. */
    private fun arityBindingIn(scope: Node, name: String): Node? {
        fun params(ps: List<Parameter>): Node? = ps.firstOrNull { checker.spineExBindingNameShadows(name, it.name) }
        fun list(ss: List<Statement>): Node? {
            for (st in ss) when (st) {
                is VariableStatement -> st.declarationList.declarations
                    .firstOrNull { checker.spineExBindingNameShadows(name, it.name) }?.let { return it }
                is FunctionDeclaration -> if (st.name?.text == name) return st
                is ClassDeclaration -> if (st.name?.text == name) return st
                is EnumDeclaration -> if (st.name.text == name) return st
                is ModuleDeclaration -> if ((st.name as? Identifier)?.text == name) return st
                is ImportEqualsDeclaration -> if (st.name.text == name) return st
                is ImportDeclaration -> {
                    val c = st.importClause ?: continue
                    if (c.name?.text == name) return st
                    when (val nb = c.namedBindings) {
                        is NamespaceImport -> if (nb.name.text == name) return st
                        is NamedImports -> if (nb.elements.any { it.name.text == name }) return st
                        else -> {}
                    }
                }
                else -> {}
            }
            return null
        }
        fun declList(n: Node?): Node? = (n as? VariableDeclarationList)?.declarations
            ?.firstOrNull { checker.spineExBindingNameShadows(name, it.name) }
        return when (scope) {
            is FunctionDeclaration -> params(scope.parameters)
            is MethodDeclaration -> params(scope.parameters)
            is Constructor -> params(scope.parameters)
            is GetAccessor -> params(scope.parameters)
            is SetAccessor -> params(scope.parameters)
            is ArrowFunction -> params(scope.parameters)
            is FunctionExpression -> if (scope.name?.text == name) scope else params(scope.parameters)
            is ClassExpression -> if (scope.name?.text == name) scope else null
            is SourceFile -> list(scope.statements)
            is Block -> list(scope.statements)
            is ModuleBlock -> list(scope.statements)
            is CaseClause -> list(scope.statements)
            is DefaultClause -> list(scope.statements)
            is ForStatement -> declList(scope.initializer)
            is ForInStatement -> declList(scope.initializer)
            is ForOfStatement -> declList(scope.initializer)
            is CatchClause -> scope.variableDeclaration?.takeIf { checker.spineExBindingNameShadows(name, it.name) }
            else -> null
        }
    }

    /** (CHK.176)(a) Does binding [b] of [name] own the signature declared at [decl]? */
    private fun arityBindingOwns(b: Node, decl: Node, name: String): Boolean {
        fun contains(): Boolean {
            var c: Node? = decl
            while (c != null) { if (c === b) return true; c = (c as NodeBase).parent }
            return false
        }
        fun annotated(t: TypeNode?): Boolean =
            t != null && !(t is KeywordTypeNode && (t.kind == SyntaxKind.AnyKeyword || t.kind == SyntaxKind.UnknownKeyword))
        return when (b) {
            is ImportDeclaration, is ImportEqualsDeclaration -> true
            is FunctionDeclaration -> decl is FunctionDeclaration && decl.name?.text == name &&
                (decl as NodeBase).parent === (b as NodeBase).parent
            is ClassDeclaration, is ClassExpression -> decl is Constructor || decl is ClassDeclaration || contains()
            is FunctionExpression -> decl === b
            is VariableDeclaration -> contains() || annotated(b.type)
            is Parameter -> contains() || annotated(b.type)
            else -> contains()
        }
    }

    /** (CHK.176)(a) Is an arity row (TS2554 / 2555 / 2556 / 2575) already drawn over exactly
     *  [start, start + length)? The LENGTH is what separates `h()()`'s two too-few rows, which
     *  tsgo anchors at one start (`h` and `h()`). */
    private fun arityRowAt(fileName: String, start: Int, length: Int): Boolean =
        checker.diagnostics.any {
            it.start == start && it.length == length && it.fileName == fileName &&
                (it.code == 2554 || it.code == 2555 || it.code == 2556 || it.code == 2575)
        }

    /**
     * Emit TS2554 for too many arguments.
     * Squiggle covers args[expectedCount] through args.last().
     */
    fun formatExpectedArgs(minParams: Int, maxParams: Int): String =
        if (minParams != maxParams) "$minParams-$maxParams" else "$maxParams"

    internal fun emitTS2554TooMany(
        minParams: Int,
        maxParams: Int,
        actual: Int,
        args: List<Expression>,
        firstExcessIdx: Int,
        source: String,
        fileName: String,
    ) {
        if (firstExcessIdx >= args.size) return
        val firstExcess = args[firstExcessIdx]
        val lastArg = args.last()
        val start = firstExcess.pos
        // Node.end includes the next token's scan position (always 1 char: ',' or ')'),
        // so subtract 1 to get the actual end of the argument text.
        val length = lastArg.end - 1 - start
        val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
        checker.diagnostics.add(Diagnostic(
            message = "Expected ${formatExpectedArgs(minParams, maxParams)} arguments, but got $actual.",
            category = DiagnosticCategory.Error,
            code = 2554,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
        ))
    }

    internal fun emitTS2554TooFew(
        minParams: Int,
        maxParams: Int,
        actual: Int,
        calleeExpr: Expression,
        source: String,
        fileName: String,
        parameters: List<Parameter> = emptyList(),
        // B434: when the callee is declared in ANOTHER (script-mode) file, the param-related
        // TS6210/TS6211 positions must resolve against the DECLARING file's source/name.
        relSource: String? = null,
        relFileName: String? = null,
    ) {
        val start = calleeExpr.pos
        val length = checker.expressionTrueEnd(calleeExpr) - start
        val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
        val rSource = relSource ?: source
        val rFileName = relFileName ?: fileName
        // Check if the first missing parameter is a binding pattern → emit TS6211 related info
        val relatedInfo = mutableListOf<Diagnostic>()
        if (parameters.isNotEmpty()) {
            // Find the first missing parameter (at index `actual`, skipping `this` params)
            val nonThisParams = parameters.filter {
                !(it.name is Identifier && (it.name).text == "this")
            }
            if (actual < nonThisParams.size) {
                val missingParam = nonThisParams[actual]
                when (val paramName = missingParam.name) {
                    is ObjectBindingPattern, is ArrayBindingPattern -> {
                        val paramStart = paramName.pos
                        val paramLen = paramName.end - paramStart
                        val (relLine, relChar) = checker.getLineAndCharacterOfPosition(rSource, paramStart)
                        relatedInfo.add(Diagnostic(
                            message = "An argument matching this binding pattern was not provided.",
                            category = DiagnosticCategory.Message,
                            code = 6211,
                            fileName = rFileName,
                            line = relLine,
                            character = relChar,
                            start = paramStart,
                            length = paramLen,
                        ))
                    }
                    is Identifier -> if (missingParam.dotDotDotToken) {
                        relatedInfo.add(restParameterNotProvided(missingParam, paramName, rSource, rFileName))
                    } else {
                        // TS6210: "An argument for 'x' was not provided."
                        // B95c (round 82): anchor at the PARAMETER's pos (Parameter.pos is captured
                        // before modifiers, so for a parameter-property `public n` it points at
                        // `public` — matching TypeScript), not the name's pos. For a plain param,
                        // Parameter.pos == name.pos so function-call TS6210 is unaffected.
                        val paramStart = missingParam.pos
                        val paramLen = (paramName.pos + paramName.text.length - paramStart).coerceAtLeast(1)
                        val (relLine, relChar) = checker.getLineAndCharacterOfPosition(rSource, paramStart)
                        relatedInfo.add(Diagnostic(
                            message = "An argument for '${paramName.text}' was not provided.",
                            category = DiagnosticCategory.Message,
                            code = 6210,
                            fileName = rFileName,
                            line = relLine,
                            character = relChar,
                            start = paramStart,
                            length = paramLen,
                        ))
                    }
                    else -> {}
                }
            }
        }
        checker.diagnostics.add(Diagnostic(
            message = "Expected ${formatExpectedArgs(minParams, maxParams)} arguments, but got $actual.",
            category = DiagnosticCategory.Error,
            code = 2554,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
            relatedInformation = relatedInfo.ifEmpty { emptyList() },
        ))
    }

    /** (CHK.181) tsgo's related row when the first missing position IS the rest parameter —
     *  only a tuple rest can be (`getArgumentArityError`'s `isRestParameter` arm, checker.go
     *  9762): TS6236 over the whole parameter, where TS6210 would name one argument. */
    private fun restParameterNotProvided(param: Parameter, name: Identifier, relSource: String, relFileName: String): Diagnostic {
        val (relLine, relChar) = checker.getLineAndCharacterOfPosition(relSource, param.pos)
        // `Node.end` runs past the NEXT token (`)` / `,`) — trim back to the parameter's text.
        var end = param.end
        while (end > param.pos && relSource.getOrNull(end - 1)?.let { it.isWhitespace() || it == ')' || it == ',' } == true) end--
        return Diagnostic(
            message = "Arguments for the rest parameter '${name.text}' were not provided.",
            category = DiagnosticCategory.Message,
            code = 6236,
            fileName = relFileName,
            line = relLine,
            character = relChar,
            start = param.pos,
            length = (end - param.pos).coerceAtLeast(1),
        )
    }

    /**
     * Emit TS2555 for too few arguments when function has rest parameters.
     * "Expected at least N arguments, but got M."
     */
    internal fun emitTS2555TooFew(
        minParams: Int,
        actual: Int,
        calleeExpr: Expression,
        source: String,
        fileName: String,
        parameters: List<Parameter> = emptyList(),
    ) {
        val start = calleeExpr.pos
        val length = checker.expressionTrueEnd(calleeExpr) - start
        val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
        val relatedInfo = mutableListOf<Diagnostic>()
        if (parameters.isNotEmpty()) {
            val nonThisParams = parameters.filter {
                !(it.name is Identifier && (it.name).text == "this")
            }
            if (actual < nonThisParams.size) {
                val missingParam = nonThisParams[actual]
                when (val paramName = missingParam.name) {
                    is Identifier -> if (missingParam.dotDotDotToken) {
                        relatedInfo.add(restParameterNotProvided(missingParam, paramName, source, fileName))
                    } else {
                        val paramStart = paramName.pos
                        val paramLen = paramName.text.length
                        val (relLine, relChar) = checker.getLineAndCharacterOfPosition(source, paramStart)
                        relatedInfo.add(Diagnostic(
                            message = "An argument for '${paramName.text}' was not provided.",
                            category = DiagnosticCategory.Message,
                            code = 6210,
                            fileName = fileName,
                            line = relLine,
                            character = relChar,
                            start = paramStart,
                            length = paramLen,
                        ))
                    }
                    is ObjectBindingPattern, is ArrayBindingPattern -> {
                        val paramStart = paramName.pos
                        val paramLen = paramName.end - paramStart
                        val (relLine, relChar) = checker.getLineAndCharacterOfPosition(source, paramStart)
                        relatedInfo.add(Diagnostic(
                            message = "An argument matching this binding pattern was not provided.",
                            category = DiagnosticCategory.Message,
                            code = 6211,
                            fileName = fileName,
                            line = relLine,
                            character = relChar,
                            start = paramStart,
                            length = paramLen,
                        ))
                    }
                    else -> {}
                }
            }
        }
        checker.diagnostics.add(Diagnostic(
            message = "Expected at least $minParams arguments, but got $actual.",
            category = DiagnosticCategory.Error,
            code = 2555,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = length,
            relatedInformation = relatedInfo.ifEmpty { emptyList() },
        ))
    }

    /** (M3.0-gap-4) The element count of a rest parameter annotated with a fixed tuple
     *  (`...args: readonly [string, string]` → 2). Null for an array rest, a tuple with a
     *  rest element of its own, or anything unrecognised — all of which keep the rest
     *  parameter's usual unbounded treatment. */
    internal fun fixedTupleLengthOfRestParam(param: Parameter?): Int? {
        val tuple = restTupleOf(param) ?: return null
        if (tuple.elements.any { it is RestType }) return null
        return tuple.elements.size
    }

    /** The tuple LITERAL a rest parameter is annotated with (through `readonly` and
     *  parentheses), or null — an alias, an array rest or anything else. */
    private fun restTupleOf(param: Parameter?): TupleType? {
        if (param == null || !param.dotDotDotToken) return null
        var annotation: TypeNode? = param.type
        while (annotation is TypeOperator || annotation is ParenthesizedType) {
            annotation = when (annotation) {
                is TypeOperator -> annotation.type
                is ParenthesizedType -> annotation.type
            }
        }
        return annotation as? TupleType
    }

    /** (M3.0-gap-4) How many arguments one argument expression contributes: 1 normally,
     *  a tuple spread's element count for `...tup`. Null when a spread's length is not
     *  known, so the caller skips the whole call rather than guessing. */
    fun argumentExpansionCount(arg: Expression): Int? {
        val spread = arg as? SpreadElement ?: return 1
        if (spread.expression is ArrayLiteralExpression) {
            return spread.expression.elements.size
        }
        (checker.getTypeOfExpression(spread.expression) as? Type.Object)?.tupleElementTypes
            ?.let { return it.size }
        val id = spread.expression as? Identifier ?: return null
        var owner: Node? = (id as NodeBase).parent
        var hops = 0
        while (owner != null && hops++ < 64) {
            val ownerParams = when (owner) {
                is FunctionDeclaration -> owner.parameters
                is FunctionExpression -> owner.parameters
                is ArrowFunction -> owner.parameters
                is MethodDeclaration -> owner.parameters
                is Constructor -> owner.parameters
                else -> null
            }
            val match = ownerParams?.firstOrNull { (it.name as? Identifier)?.text == id.text }
            if (match != null) return fixedTupleLengthOfRestParam(match)
            owner = (owner as? NodeBase)?.parent
        }
        return null
    }

    /**
     * (CHK.98)(d) tsc's ARITY view of an argument list carrying a spread —
     * `getEffectiveCallArguments` (checker.ts:36260) followed by the spread clause of
     * `hasCorrectArity` (:35672), and `getArgumentArityError`'s first line (:36411).
     *
     * A spread whose operand is a FIXED-length tuple (or an array literal, which tsc
     * types as a tuple in that position) expands into that many ordinary arguments; a
     * spread whose operand is anything else SURVIVES, and from then on the argument
     * count is unknown, so the only arity question left is tsc's: the surviving
     * spread's index must be at or past the signature's minimum, and either the
     * signature has a rest parameter or the index is inside its parameter list.
     * Otherwise the call is `A spread argument must either have a tuple type or be
     * passed to a rest parameter.` (TS2556) — and NEVER a count (TS2554): measured on
     * both references, `f(1, 2, ...xs)` against `f(a: number)` prints TS2556 alone,
     * where this checker printed `Expected 1 arguments, but got 3.`, and `f(...xs)`
     * against `f(a?: number, b?: number)` is legal (index 0 is at the minimum and
     * inside the list), where this checker reported it.
     *
     * [Spread.decided] is what keeps the rule from inventing a row: an operand this
     * checker cannot classify (an `any`, an unresolved name, a type parameter, an
     * un-annotated body-local) makes [Spread.index] a LOWER BOUND, and the only
     * verdict a lower bound licenses is "already past the parameter list of a
     * signature with no rest parameter", which no expansion can undo. Everything else
     * stays silent, in the direction every gate here can see.
     */
    internal sealed class SpreadArityView {
        /** No spread survives: [count] is the effective argument count and [expansion]
         *  the per-ORIGINAL-argument expansion, so a too-many report can anchor on the
         *  original argument whose expansion crosses the maximum. */
        class Expanded(val count: Int, val expansion: List<Int>) : SpreadArityView()
        /** A spread survives at effective [index], living in the original argument [arg]. */
        class Spread(val index: Int, val arg: SpreadElement, val decided: Boolean) : SpreadArityView()
    }

    /** How one spread operand expands: a fixed number of elements, a fixed prefix
     *  followed by a rest slot, positively not a tuple, or nothing this checker can say. */
    private sealed class SpreadOperandShape {
        class Fixed(val count: Int) : SpreadOperandShape()
        class RestTail(val fixedBefore: Int) : SpreadOperandShape()
        object NonTuple : SpreadOperandShape()
        object Unknown : SpreadOperandShape()
    }

    internal fun spreadArityView(args: List<Expression>): SpreadArityView {
        var effective = 0
        val expansion = ArrayList<Int>(args.size)
        for (arg in args) {
            if (arg !is SpreadElement) { effective++; expansion.add(1); continue }
            when (val shape = classifySpreadOperand(arg.expression)) {
                is SpreadOperandShape.Fixed -> { effective += shape.count; expansion.add(shape.count) }
                is SpreadOperandShape.RestTail ->
                    return SpreadArityView.Spread(effective + shape.fixedBefore, arg, decided = true)
                SpreadOperandShape.NonTuple -> return SpreadArityView.Spread(effective, arg, decided = true)
                SpreadOperandShape.Unknown -> return SpreadArityView.Spread(effective, arg, decided = false)
            }
        }
        return SpreadArityView.Expanded(effective, expansion)
    }

    /** The spread clause of tsc's `hasCorrectArity`, inverted: true when the call MUST be
     *  reported as TS2556 against a signature with [minParams] required parameters,
     *  [paramCount] parameters in all and, when [hasRest], a rest parameter. */
    internal fun spreadArityFails(
        view: SpreadArityView.Spread, minParams: Int, paramCount: Int, hasRest: Boolean,
    ): Boolean =
        if (view.decided) !(view.index >= minParams && (hasRest || view.index < paramCount))
        else !hasRest && view.index >= paramCount

    /** The ORIGINAL argument index at which an expanded count first exceeds [maxParams]
     *  — where tsc's too-many report anchors (a synthetic argument carries its spread's
     *  span). */
    fun firstExcessArgIndex(view: SpreadArityView.Expanded, maxParams: Int): Int {
        var running = 0
        for ((i, n) in view.expansion.withIndex()) {
            running += n
            if (running > maxParams) return i
        }
        return view.expansion.size
    }

    internal fun emitTS2556(spread: SpreadElement, source: String, fileName: String) {
        val start = spread.pos
        val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
        checker.diagnostics.add(Diagnostic(
            message = "A spread argument must either have a tuple type or be passed to a rest parameter.",
            category = DiagnosticCategory.Error, code = 2556, fileName = fileName,
            line = line, character = character, start = start,
            length = (checker.expressionTrueEnd(spread.expression) - start).coerceAtLeast(1),
        ))
    }

    /**
     * What a spread operand expands to. THREE routes, in an order that is load-bearing:
     * an array literal is syntactic (tsc types it as a tuple in this position); an
     * IDENTIFIER that names an enclosing parameter or a scope-space local is classified
     * by that DECLARATION's annotation — the arity walkers run at the spine hook, under
     * the FILE-LEVEL ambient (round 911), where `getTypeOfExpression` answers a rest
     * parameter `any` and a body-local by whatever same-named file-level binding exists,
     * i.e. the one way this rule could invent a row (a file-level `number[]` shadowed
     * by a body-local tuple); only a name with no such binding is read from its type.
     */
    private fun classifySpreadOperand(operand: Expression): SpreadOperandShape {
        if (operand is ArrayLiteralExpression) {
            return if (operand.elements.any { it is SpreadElement }) SpreadOperandShape.Unknown
            else SpreadOperandShape.Fixed(operand.elements.size)
        }
        if (operand is Identifier) {
            spreadOperandDeclaration(operand)?.let { decl ->
                val annotation = when (decl) {
                    is Parameter -> decl.type
                    is VariableDeclaration -> decl.type
                    else -> null
                }
                if (annotation != null) return classifySpreadAnnotation(annotation)
                // An un-annotated local whose initializer is a plain array literal is an
                // ARRAY in tsc (no contextual type, so no tuple), never a tuple.
                val init = (decl as? VariableDeclaration)?.initializer
                return if (init is ArrayLiteralExpression && init.elements.none { it is SpreadElement })
                    SpreadOperandShape.NonTuple else SpreadOperandShape.Unknown
            }
        }
        return spreadShapeOfType(checker.getTypeOfExpression(operand))
    }

    /**
     * The enclosing-function PARAMETER or enclosing-block LOCAL an operand identifier
     * names — the declarations the file-level ambient cannot see — or null for neither.
     * A SYNTACTIC innermost-first walk of the enclosing statement lists (round 935's
     * shape), because (INV.0)'s scope-space consult is override-only and gated, and what
     * this needs is the DECLARATION's annotation, not a resolved type: measured, a
     * body-local `const t: [number, number]` shadowing a file-level `number[]` read the
     * file-level array through the resolver route and reported a false TS2556.
     */
    private fun spreadOperandDeclaration(id: Identifier): Node? {
        var cur: Node? = (id as NodeBase).parent
        var hops = 0
        while (cur != null && hops++ < 64) {
            when (cur) {
                is Block -> for (st in cur.statements) {
                    val vs = st as? VariableStatement ?: continue
                    vs.declarationList.declarations.firstOrNull { (it.name as? Identifier)?.text == id.text }
                        ?.let { return it }
                }
                is FunctionDeclaration -> cur.parameters.firstOrNull { (it.name as? Identifier)?.text == id.text }?.let { return it }
                is FunctionExpression -> cur.parameters.firstOrNull { (it.name as? Identifier)?.text == id.text }?.let { return it }
                is ArrowFunction -> cur.parameters.firstOrNull { (it.name as? Identifier)?.text == id.text }?.let { return it }
                is MethodDeclaration -> cur.parameters.firstOrNull { (it.name as? Identifier)?.text == id.text }?.let { return it }
                is Constructor -> cur.parameters.firstOrNull { (it.name as? Identifier)?.text == id.text }?.let { return it }
                else -> {}
            }
            cur = (cur as? NodeBase)?.parent
        }
        return null
    }

    /** [classifySpreadOperand] for an ANNOTATION: a tuple type node is read element by
     *  element (a `readonly` operator and parentheses are transparent, an optional
     *  element COUNTS — tsc pushes one synthetic argument per element), an array type
     *  node is positively not a tuple, and anything else is resolved and classified as
     *  a type. */
    private fun classifySpreadAnnotation(node: TypeNode): SpreadOperandShape {
        var annotation: TypeNode = node
        while (true) {
            annotation = when {
                annotation is TypeOperator && annotation.operator == SyntaxKind.ReadonlyKeyword -> annotation.type
                annotation is ParenthesizedType -> annotation.type
                else -> break
            }
        }
        return when (annotation) {
            is ArrayType -> SpreadOperandShape.NonTuple
            is TupleType -> {
                for ((i, e) in annotation.elements.withIndex()) {
                    val rest = e is RestType || (e is NamedTupleMember && e.dotDotDotToken)
                    if (rest) {
                        val inner = if (e is RestType) e.type else (e as NamedTupleMember).type
                        return if (inner is TupleType) SpreadOperandShape.Unknown
                        else SpreadOperandShape.RestTail(i)
                    }
                }
                SpreadOperandShape.Fixed(annotation.elements.size)
            }
            else -> checker.getTypeFromTypeNodeSafe(annotation)?.let { spreadShapeOfType(it) }
                ?: SpreadOperandShape.Unknown
        }
    }

    /** [classifySpreadOperand] for a resolved TYPE. A union is not a tuple in tsc even
     *  when every constituent is one (`isTupleType` is false for it), but one carrying a
     *  type parameter or an `any` may be a resolution failure of ours, so it is refused. */
    private fun spreadShapeOfType(t: Type): SpreadOperandShape {
        if (t === anyType || t === errorType || t is Type.TypeParam) return SpreadOperandShape.Unknown
        if (t is Type.Union || t is Type.Intersection) {
            val parts = if (t is Type.Union) t.types else (t as Type.Intersection).types
            return if (parts.any { it === anyType || it === errorType || it is Type.TypeParam })
                SpreadOperandShape.Unknown else SpreadOperandShape.NonTuple
        }
        if (t is Type.Object && t !is Type.Interface) {
            val elems = t.tupleElementTypes
            if (elems != null) {
                return if (t.tupleHasRest) SpreadOperandShape.RestTail(t.tupleRestIndex)
                else SpreadOperandShape.Fixed(elems.size)
            }
        }
        return SpreadOperandShape.NonTuple
    }

    /**
     * (CHK.33) The arity [sig]'s own DECLARATION states, or null where the declaration is
     * absent or not function-like.
     *
     * **`Signature.parameters` IS NOT THE DECLARATION'S PARAMETER LIST.**
     * [getParameterSymbols] drops every `ObjectBindingPattern` / `ArrayBindingPattern`
     * parameter (its `forSignatureDisplay` opt-in is the only place a placeholder is
     * minted), while [requiredParameterCount] — and so [Signature.minArgumentCount] —
     * COUNTS it. Any reader that takes its MAXIMUM from `parameters.size` and its MINIMUM
     * from `minArgumentCount` therefore prints an IMPOSSIBLE range for
     * `m({ text }: T): R`: `Expected 1-0 arguments, but got 1.` on a call that is legal
     * TypeScript, which is self-evidently broken output needing no reference to
     * adjudicate. Round 446 recovered the true arity at the property-access reader
     * ([checkTs2554ForPropertyAccessCall]); this is that block extracted so the UNION
     * reader ([unionCalleeArityDiagnostic]) shares it rather than carrying a fourth copy
     * of the declaration-to-parameters map.
     *
     * [paramInfo] is the right computation to reuse: it skips the `this` pseudo-parameter
     * and the rest tail exactly as [requiredParameterCount] does, counts a binding-pattern
     * parameter as an ordinary one, and reports `hasRest` — so `minParams`/`maxParams`
     * are the arity the user WROTE.
     *
     * NOT a fix at [getParameterSymbols]: widening `Signature.parameters`' MEMBERSHIP is
     * a repo-wide blast radius for a local defect, and CLAUDE.md records that exclusion as
     * deliberate. The prescription there is the one applied here — *read `sig.declaration`'s
     * own list*.
     *
     * **The kind list is round 446's PLUS `FunctionType` / `ConstructorType`, and the two
     * additions are a MEASURED false-positive fix rather than tidiness.** A member whose
     * type is written as a function TYPE carries that node as its signature's declaration
     * ([buildSignatureForFunctionLikeTypeNode]), and such a node may perfectly well spell a
     * binding pattern — `declare const host: { m: ({ a }: O) => void }; host.m(o)` is legal
     * TypeScript that tsgo 7.0.2 accepts in silence and that this compiler answered
     * `Expected 1-0 arguments, but got 1.` before the arms were added. An options-bag
     * callback property is one of the commonest shapes in real TypeScript, so the omission
     * was not academic.
     *
     * `GetAccessor` / `SetAccessor` are round 446's own arms and are kept VERBATIM, but a
     * census of every `Signature(` construction in this module says they are DEAD: no
     * signature is ever built with an accessor declaration (an accessor resolves into a
     * Property symbol instead, `MemberResolver`). Recorded rather than deleted — an arm
     * proved unreachable today is a barrier tomorrow, and deleting it would make the
     * extraction no longer verbatim.
     *
     * Still NOT covered, and measured: `IndexSignature`. There is no `MethodSignature` /
     * `CallSignature` / `ConstructSignature` node class in this codebase — the parser
     * reuses `MethodDeclaration` for an interface method, a call signature (name `""`) and
     * a construct signature (name `"new"`) alike — so those three are already covered by the
     * `MethodDeclaration` arm.
     */
    internal fun signatureDeclaredArity(sig: Signature): FuncParamInfo? =
        when (val d = sig.declaration) {
            is FunctionDeclaration -> d.parameters
            is MethodDeclaration -> d.parameters
            is FunctionExpression -> d.parameters
            is ArrowFunction -> d.parameters
            is Constructor -> d.parameters
            is FunctionType -> d.parameters
            is ConstructorType -> d.parameters
            is GetAccessor -> d.parameters
            is SetAccessor -> d.parameters
            else -> null
        }?.let { checker.paramInfo(it) }

    /**
     * (CHK.176)(b) tsgo's `getMinArgumentCount` for a CALL (relater.go `getMinArgumentCountEx`,
     * no flags): [info]'s required count less its trailing run of `void`-accepting parameters
     * ([typeAcceptsVoid]), so `g("a")` for `g(s: string, v: void)` has correct arity and
     * `g()` reads `Expected 1-2 arguments`. tsgo's `hasCorrectArity` then walks the missing
     * positions with the same `acceptsVoid` test, which is implied: the last position of the
     * trimmed count is not void-accepting, so a call is too short exactly when it is below it.
     *
     * The ONE home of the call-side rule, shared by [SignatureArity.callArityFails] and every arity emitter
     * that holds a [FuncParamInfo] (the name-based walkers and the property-access reader).
     * Call it only on a too-few path: it resolves parameter types. A parameter's type is its
     * [sig] symbol's where one is given (an instantiated signature), else its annotation's;
     * an un-annotated parameter ends the run (no `void` reaches it here).
     */
    internal fun callMinArgumentCount(info: FuncParamInfo, sig: Signature? = null): Int =
        callMinArgumentCount(info.minParams, info.parameters, sig)

    /** [callMinArgumentCount] over an overload set: the smallest trimmed minimum (the set's
     *  own [FuncParamInfo.minParams] when no per-overload shape was collected). */
    fun overloadCallMin(info: FuncParamInfo): Int =
        if (info.overloadSigs.isEmpty()) callMinArgumentCount(info)
        else info.overloadSigs.minOf { callMinArgumentCount(it.minParams, it.parameters) }

    fun callMinArgumentCount(minParams: Int, parameters: List<Parameter>, sig: Signature? = null): Int {
        var min = minParams
        if (min <= 0) return min
        val positional = parameters.filter {
            !it.dotDotDotToken && !((it.name as? Identifier)?.text == "this")
        }
        for (i in min - 1 downTo 0) {
            val p = positional.getOrNull(i) ?: break
            val t = sig?.parameters?.firstOrNull { it.valueDeclaration === p }?.let { checker.getTypeOfSymbol(it) }
                ?: p.type?.let { checker.getTypeFromTypeNode(it) }
                ?: break
            if (!typeAcceptsVoid(t)) break
            min = i
        }
        return min
    }

    /** (CHK.176) tsgo's `acceptsVoid` over a type or any union constituent — the rule
     *  [relationMinArgumentCount] and [callMinArgumentCount] share. */
    fun typeAcceptsVoid(t: Type): Boolean =
        if (t is Type.Union) t.types.any { it.flags.hasAny(TypeFlags.Void) } else t.flags.hasAny(TypeFlags.Void)
}
