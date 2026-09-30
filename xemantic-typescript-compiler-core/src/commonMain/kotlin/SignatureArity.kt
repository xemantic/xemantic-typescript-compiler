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
import com.xemantic.typescript.compiler.Checker.SpreadArityView

/**
 * (INV.0) (P18.232) — the (CHK.175)/(CHK.176)(a) SIGNATURE-BASED ARITY reader: tsgo's
 * `hasCorrectArity` for a spread-free argument list ([callArityFails]) and its
 * `getArgumentArityError` over the signatures a call resolved to (TS2554 / TS2555 / TS2575,
 * [reportSignatureArity]; TS2556 for a surviving spread, [reportSpreadSignatureArity]), with
 * the declaration-trust, identifier-callee-binding and overload-completeness guards and the
 * span dedup against the name-based arity walkers. Extracted VERBATIM from `Checker.kt` (one
 * contiguous span, 170417-170762); every Checker member it reads is reached through
 * [checker]. The call-side minimum (`callMinArgumentCount`, `overloadCallMin`,
 * `typeAcceptsVoid`) stayed on [Checker]: it is shared with the name-based walkers and the
 * relation. Ambient reads: `docs/inversion-ambient-ledger.md` row 14.
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
        if (n >= sig.minArgumentCount && n <= sig.parameters.size && !endsInTupleRest(sig)) return false
        if (args.any { it is SpreadElement }) return false
        val info = checker.signatureDeclaredArity(sig) ?: return false
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
        return p.dotDotDotToken && checker.fixedTupleLengthOfRestParam(p) != null
    }

    /** (CHK.176)(a) tsgo's call arity of one signature: `getMinArgumentCount` (void-trimmed),
     *  `getParameterCount` and `hasEffectiveRestParameter`, with a fixed-tuple rest expanded
     *  into its elements (`...a: [string, number?]` is one required and one optional). */
    private class CallArity(val min: Int, val max: Int, val hasRest: Boolean) {
        fun fails(n: Int) = (!hasRest && n > max) || n < min
    }

    private fun callArity(info: FuncParamInfo, sig: Signature?, trimBelow: Int): CallArity {
        if (info.hasRest) {
            val rest = info.parameters.lastOrNull { it.dotDotDotToken }
            var ann: TypeNode? = rest?.type
            while (ann is TypeOperator || ann is ParenthesizedType) {
                ann = if (ann is TypeOperator) ann.type else (ann as ParenthesizedType).type
            }
            val tuple = ann as? TupleType
            if (tuple != null && tuple.elements.none { it is RestType }) {
                // The parser records a `?` element in [TupleType.elementOptional].
                val opt = tuple.elementOptional
                val required = tuple.elements.indices.indexOfLast { opt?.getOrNull(it) != true } + 1
                val min = if (required > 0) info.maxParams + required
                    else if (trimBelow < info.minParams) checker.callMinArgumentCount(info, sig) else info.minParams
                return CallArity(min, info.maxParams + tuple.elements.size, hasRest = false)
            }
        }
        // The void trim resolves parameter types — only below the declared minimum
        // ([trimBelow] = the argument count, or -1 for the reported range).
        val min = if (trimBelow < info.minParams) checker.callMinArgumentCount(info, sig) else info.minParams
        return CallArity(min, info.maxParams, info.hasRest)
    }

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
            val info = checker.signatureDeclaredArity(sig) ?: return
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
                    checker.emitTS2555TooFew(
                        minCount, n, errorNode, source, fileName,
                        if (declFile == null || declFile.fileName == fileName) params else emptyList(),
                    )
                } else {
                    checker.emitTS2554TooFew(
                        minCount, maxCount, n, errorNode, source, fileName, params,
                        declFile?.text, declFile?.fileName,
                    )
                }
            }
            n > maxCount && maxCount >= 0 -> {
                // emitTS2554TooMany's own span, so a row the name walker drew matches.
                if (arityRowAt(fileName, args[maxCount].pos, args.last().end - 1 - args[maxCount].pos)) return
                checker.emitTS2554TooMany(minCount, maxCount, n, args, maxCount, source, fileName)
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
        val view = checker.spreadArityView(args) as? SpreadArityView.Spread ?: return false
        val info = checker.signatureDeclaredArity(sig) ?: return false
        if (!arityDeclTrusted(sig) || endsInTupleRest(sig)) return false
        if (!checker.spreadArityFails(view, info.minParams, info.maxParams, info.hasRest)) return false
        val length = (checker.expressionTrueEnd(view.arg.expression) - view.arg.pos).coerceAtLeast(1)
        if (!arityRowAt(fileName, view.arg.pos, length)) checker.emitTS2556(view.arg, source, fileName)
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
}
