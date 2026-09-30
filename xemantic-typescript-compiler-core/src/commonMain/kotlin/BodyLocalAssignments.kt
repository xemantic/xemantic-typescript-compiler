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
 * (CHK.173) B6 — the assignments of a name that REACH a read, for the unresolved-RHS
 * veto of the body-local TS1804x arm (`Checker.bodyLocalReceiverDeclaredType`).
 *
 * A breadth-first walk over the flow graph backwards from the read's flow node,
 * crossing a closure's [FlowStart.outerFlow] into the enclosing function (a captured
 * local's assignments live there), bounded by [cap] visited nodes. Every
 * [FlowAssignment] whose node assigns [name] is reported with its right-hand side:
 * a `VariableDeclaration` with an identifier name reports its initializer (null for
 * a declaration without one — the variable then holds `undefined`), a
 * `BinaryExpression` whose left operand is the identifier reports its right operand
 * for `=`, `??=`, `||=` and `&&=` (arithmetic compound assignments cannot store a
 * nullish value and are skipped). A name match, not a symbol match: a same-named
 * shadow's assignment is reported too, which can only add refusals.
 *
 * The walk does NOT stop at an assignment — every assignment on any path behind the
 * read is reported, which is what the veto needs (an `any` right-hand side anywhere
 * behind the read means the narrowing may rest on a value this checker cannot type).
 */
internal object BodyLocalAssignments {

    /** One reaching assignment: [rhs] is null for a declaration with no initializer. */
    class Reaching(val rhs: Expression?)

    fun reaching(start: FlowNode, name: String, cap: Int = 4000): List<Reaching> {
        val out = ArrayList<Reaching>()
        val seen = HashSet<Int>()
        val queue = ArrayDeque<FlowNode>()
        queue.addLast(start)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < cap) {
            val f = queue.removeFirst()
            // Flow-node ids restart per file, and a closure's outerFlow stays in the same
            // file, so the id alone identifies a node within one walk.
            if (!seen.add(f.id)) continue
            when (f) {
                is FlowStart -> f.outerFlow?.let { queue.addLast(it) }
                is FlowUnreachable -> {}
                is FlowBranchLabel -> queue.addAll(f.antecedents)
                is FlowLoopLabel -> queue.addAll(f.antecedents)
                is FlowAssignment -> {
                    when (val n = f.node) {
                        is VariableDeclaration ->
                            if ((n.name as? Identifier)?.text == name) out.add(Reaching(n.initializer))
                        is BinaryExpression ->
                            if ((n.left as? Identifier)?.text == name && n.operator in STORING) out.add(Reaching(n.right))
                        else -> {}
                    }
                    queue.addLast(f.antecedent)
                }
                is FlowCondition -> queue.addLast(f.antecedent)
                is FlowSwitchClause -> queue.addLast(f.antecedent)
                is FlowCall -> queue.addLast(f.antecedent)
                is FlowArrayMutation -> queue.addLast(f.antecedent)
            }
        }
        return out
    }

    /**
     * True when [read] sits in an object-literal or class-expression METHOD / ACCESSOR
     * nested below [decl]'s own scope: tsgo extends a captured constant's flow container
     * through one (`isObjectLiteralOrClassExpressionMethodOrAccessor`), while this
     * checker's flow graph gives only an arrow / function expression an outer flow — the
     * read would see the declared type where tsgo sees the narrowed one.
     */
    fun readCrossesUnmodeledContainer(read: Node, decl: Node): Boolean {
        val declAncestors = HashSet<Int>()
        var d: Node? = (decl as NodeBase).parent
        var hops = 0
        while (d != null && hops++ < 4096) { declAncestors.add((d as NodeBase).nodeId); d = d.parent }
        var cur: Node? = (read as NodeBase).parent
        hops = 0
        while (cur != null && cur !is SourceFile && hops++ < 4096) {
            if ((cur as NodeBase).nodeId in declAncestors) return false
            if (cur is MethodDeclaration || cur is GetAccessor || cur is SetAccessor) {
                val p = cur.parent
                if (p is ObjectLiteralExpression || p is ClassExpression) return true
            }
            cur = cur.parent
        }
        return false
    }

    /**
     * True when a condition reaching [start] tests an element access rooted at [root]
     * (`if (!t[0]) return; const [a] = t`): tsgo narrows `t[0]` there, this checker's
     * narrowing does not ((CHK.173) B5c's residue r4 / r5), so a leaf destructured from
     * [root] would keep a nullish member tsgo removed.
     */
    fun conditionTestsElementOf(start: FlowNode, root: String, cap: Int = 4000): Boolean {
        val seen = HashSet<Int>()
        val queue = ArrayDeque<FlowNode>()
        queue.addLast(start)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < cap) {
            val f = queue.removeFirst()
            if (!seen.add(f.id)) continue
            when (f) {
                is FlowStart -> f.outerFlow?.let { queue.addLast(it) }
                is FlowUnreachable -> {}
                is FlowBranchLabel -> queue.addAll(f.antecedents)
                is FlowLoopLabel -> queue.addAll(f.antecedents)
                is FlowAssignment -> queue.addLast(f.antecedent)
                is FlowCondition -> {
                    if (mentionsElementOf(f.expression, root)) return true
                    queue.addLast(f.antecedent)
                }
                is FlowSwitchClause -> {
                    if (mentionsElementOf(f.switchStatement.expression, root)) return true
                    queue.addLast(f.antecedent)
                }
                is FlowCall -> queue.addLast(f.antecedent)
                is FlowArrayMutation -> queue.addLast(f.antecedent)
            }
        }
        return false
    }

    /**
     * The other operands of every equality comparison, in a condition reaching [start],
     * one side of which is an optional chain rooted at [name] (`x?.p === y`): tsgo
     * narrows `x` there by the other operand's type (N10), so the caller refuses when
     * this checker cannot type one of them.
     */
    fun optionalChainComparands(start: FlowNode, name: String, cap: Int = 4000): List<Expression> {
        val out = ArrayList<Expression>()
        val seen = HashSet<Int>()
        val queue = ArrayDeque<FlowNode>()
        queue.addLast(start)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < cap) {
            val f = queue.removeFirst()
            if (!seen.add(f.id)) continue
            when (f) {
                is FlowStart -> f.outerFlow?.let { queue.addLast(it) }
                is FlowUnreachable -> {}
                is FlowBranchLabel -> queue.addAll(f.antecedents)
                is FlowLoopLabel -> queue.addAll(f.antecedents)
                is FlowAssignment -> queue.addLast(f.antecedent)
                is FlowCondition -> {
                    collectComparands(f.expression, name, out)
                    queue.addLast(f.antecedent)
                }
                is FlowSwitchClause -> queue.addLast(f.antecedent)
                is FlowCall -> queue.addLast(f.antecedent)
                is FlowArrayMutation -> queue.addLast(f.antecedent)
            }
        }
        return out
    }

    private fun collectComparands(expr: Node, name: String, out: MutableList<Expression>) {
        val stack = ArrayDeque<Node>()
        stack.addLast(expr)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n is BinaryExpression && n.operator in EQUALITY) {
                if (optionalChainRootedAt(n.left, name)) out.add(n.right)
                if (optionalChainRootedAt(n.right, name)) out.add(n.left)
            }
            forEachChild(n) { stack.addLast(it) }
        }
    }

    private fun optionalChainRootedAt(e: Expression, name: String): Boolean {
        var r: Expression = e
        var optional = false
        while (true) r = when (r) {
            is PropertyAccessExpression -> { if (r.questionDotToken) optional = true; r.expression }
            is ElementAccessExpression -> { if (r.questionDotToken) optional = true; r.expression }
            is CallExpression -> { if (r.questionDotToken) optional = true; r.expression }
            is ParenthesizedExpression -> r.expression
            is NonNullExpression -> r.expression
            else -> break
        }
        return optional && (r as? Identifier)?.text == name
    }

    private val EQUALITY = setOf(
        SyntaxKind.EqualsEqualsEquals, SyntaxKind.ExclamationEqualsEquals,
        SyntaxKind.EqualsEquals, SyntaxKind.ExclamationEquals,
    )

    private fun mentionsElementOf(expr: Node, root: String): Boolean {
        val stack = ArrayDeque<Node>()
        stack.addLast(expr)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n is ElementAccessExpression) {
                var r: Expression = n.expression
                while (true) r = when (r) {
                    is PropertyAccessExpression -> r.expression
                    is ElementAccessExpression -> r.expression
                    is ParenthesizedExpression -> r.expression
                    is NonNullExpression -> r.expression
                    else -> break
                }
                if ((r as? Identifier)?.text == root) return true
            }
            forEachChild(n) { stack.addLast(it) }
        }
        return false
    }

    private val STORING = setOf(
        SyntaxKind.Equals,
        SyntaxKind.QuestionQuestionEquals,
        SyntaxKind.BarBarEquals,
        SyntaxKind.AmpersandAmpersandEquals,
    )
}
