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
 */


package com.xemantic.typescript.compiler

/**
 * (CHK.173) S-G1c — is a reference evaluated inside the NON-NULLISH part of an optional
 * chain that is guarded by the same reference?
 *
 * tsgo's binder gives the rest of an optional chain a `TrueCondition` on each `?.`
 * link's receiver (`bindOptionalChain`), so in `t?.[t.length - 1]`, `d?.m(d.p)`,
 * `d?.a.b(d.p)` and `(d?.a)?.push(d.p)` the second `t`/`d` is narrowed to non-nullish.
 * Our flow builder mints no node for a `?.` (Flow.kt, the PA / EA / Call arms bind the
 * receiver then the arguments), and a label at every `?.` in the program was REFUSED as
 * the price of 5 real rows. This is the syntactic stand-in, asked only where an emitter
 * is about to report the reference as possibly nullish.
 *
 * The walk goes UP from [ref] by parent pointers. At a call ARGUMENT or an element-access
 * INDEX it asks whether that call / access is a later part of an optional chain one of
 * whose `?.` links has a receiver (parens stripped, tsgo `optionalChainContainsReference`)
 * that IS the reference or is itself an optional chain containing it. The chain is
 * walked down through `.` / `[]` / `()` / `!` only — a PAREN ends it (`(d?.a).push(d.p)`
 * reports in tsgo; `(d?.a)?.push(d.p)` does not, because there the paren is a RECEIVER).
 *
 * A closure boundary (arrow / function expression) is crossed only when [mayCrossClosure]
 * says the flow walk itself would carry narrowing in — the same gate as
 * `Checker.outerFlowForCapturedName` (not a closure local, not reassigned at/after the
 * closure, not a captured `var`). Every other function-like or class element ends the
 * walk (an object-literal METHOD is narrowed by tsgo and is a recorded residue here).
 *
 * An assignment to the reference inside the guarding argument list, evaluated before
 * the read, defeats the guard (`d?.m((d = undefined, d.p))` reports in tsgo); the scan is
 * syntactic (any assignment operator or `++`/`--` whose target path is the reference or a
 * prefix of it). An assignment AFTER a closure is the closure gate's job.
 */
internal fun optionalChainGuardsReference(
    ref: Expression,
    name: String,
    refPath: (Expression) -> String?,
    chainContainsRef: (Expression, String) -> Boolean,
    mayCrossClosure: (Node) -> Boolean,
): Boolean {
    var child: Node = ref
    var p: Node? = (ref as NodeBase).parent
    while (p != null) {
        when (p) {
            is CallExpression ->
                if (p.arguments.anyIdentical(child) &&
                    ocgChainGuards(p, name, refPath, chainContainsRef) &&
                    !ocgAssignsBefore(p.arguments, ref, name, refPath)
                ) return true
            is ElementAccessExpression ->
                if (p.argumentExpression === child &&
                    ocgChainGuards(p, name, refPath, chainContainsRef) &&
                    !ocgAssignsBefore(listOf(p.argumentExpression), ref, name, refPath)
                ) return true
            is ArrowFunction, is FunctionExpression -> if (!mayCrossClosure(p)) return false
            is FunctionDeclaration, is ClassDeclaration, is ModuleDeclaration,
            is ClassElement, is SourceFile -> return false
            else -> {}
        }
        child = p
        p = (p as NodeBase).parent
    }
    return false
}

/** Walk [link]'s chain down through `.` / `[]` / `()` / `!` (a paren ends it) for a `?.` link guarded by [name]. */
private fun ocgChainGuards(
    link: Expression, name: String,
    refPath: (Expression) -> String?, chainContainsRef: (Expression, String) -> Boolean,
): Boolean {
    var e: Expression = link
    while (true) {
        val (optional, receiver) = when (e) {
            is PropertyAccessExpression -> e.questionDotToken to e.expression
            is ElementAccessExpression -> e.questionDotToken to e.expression
            is CallExpression -> e.questionDotToken to e.expression
            is NonNullExpression -> false to e.expression
            else -> return false
        }
        if (optional) {
            var r = receiver
            while (r is ParenthesizedExpression) r = r.expression
            if (refPath(r) == name || chainContainsRef(r, name)) return true
        }
        e = receiver
    }
}

/**
 * Does an expression under [roots] that is evaluated BEFORE [ref] (it starts earlier and
 * is not one of [ref]'s ancestors, so `d?.m(d = f(d.p))` still reads `d` first) assign,
 * or `++`/`--`, the reference [name] or a path prefix of it? `d?.m((d.p, d = undefined))`
 * stays guarded in tsgo; `d?.m((d = undefined, d.p))` does not.
 */
private fun ocgAssignsBefore(roots: List<Node>, ref: Node, name: String, refPath: (Expression) -> String?): Boolean {
    fun before(n: Node): Boolean {
        if (n.pos >= ref.pos) return false
        var a: Node? = (ref as NodeBase).parent
        while (a != null) {
            if (a === n) return false
            a = (a as NodeBase).parent
        }
        return true
    }
    fun hits(target: Expression): Boolean {
        var t = target
        while (t is ParenthesizedExpression) t = t.expression
        val path = refPath(t) ?: return false
        return path == name ||
            (name.length > path.length && name.startsWith(path) && (name[path.length] == '.' || name[path.length] == '['))
    }
    val work = ArrayDeque<Node>(roots)
    while (work.isNotEmpty()) {
        val n = work.removeLast()
        when (n) {
            is BinaryExpression -> if (ocgIsAssignmentOperator(n.operator) && hits(n.left) && before(n)) return true
            is PrefixUnaryExpression ->
                if ((n.operator == SyntaxKind.PlusPlus || n.operator == SyntaxKind.MinusMinus) && hits(n.operand) && before(n)) return true
            is PostfixUnaryExpression ->
                if ((n.operator == SyntaxKind.PlusPlus || n.operator == SyntaxKind.MinusMinus) && hits(n.operand) && before(n)) return true
            else -> {}
        }
        forEachChild(n) { work.add(it) }
    }
    return false
}

private fun ocgIsAssignmentOperator(op: SyntaxKind): Boolean = when (op) {
    SyntaxKind.Equals, SyntaxKind.PlusEquals, SyntaxKind.MinusEquals,
    SyntaxKind.AsteriskEquals, SyntaxKind.AsteriskAsteriskEquals, SyntaxKind.SlashEquals,
    SyntaxKind.PercentEquals, SyntaxKind.AmpersandEquals, SyntaxKind.BarEquals, SyntaxKind.CaretEquals,
    SyntaxKind.LessThanLessThanEquals, SyntaxKind.GreaterThanGreaterThanEquals,
    SyntaxKind.GreaterThanGreaterThanGreaterThanEquals,
    SyntaxKind.BarBarEquals, SyntaxKind.AmpersandAmpersandEquals, SyntaxKind.QuestionQuestionEquals -> true
    else -> false
}
