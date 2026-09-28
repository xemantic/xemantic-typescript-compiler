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
 * (CHK.173) Round B1 — does a flow ASSIGNMENT write the same BINDING a flow walk's
 * REFERENCE reads?
 *
 * The flow walks match a `FlowAssignment` to their reference by NAME
 * ([Checker.flowAssignmentMightNarrow], [Checker.flowAssignmentTargetsName]), where tsgo
 * matches by resolved SYMBOL. So a same-named `let` / `const` declared in a nested block,
 * a `switch` case block, a `for` header or a `catch` clause re-narrowed the OUTER variable
 * for every read after that scope — `if (!s) return; { const s = g(); } s.length` read the
 * inner `g()` and reported TS18047 on legal code.
 *
 * [writesOtherBinding] answers true when the assignment's target resolves to a
 * block-scoped binding whose scope does NOT contain the reference. The scope is the
 * innermost node between the target and its function (or file) that declares the name
 * block-scoped: a statement [Block] that is not a function body (a file-level block
 * counts), every clause of a [SwitchStatement] (one scope), a `for` / `for…in` / `for…of`
 * header, a [CatchClause]'s variable. `var` is function-scoped and never counts — an inner
 * `var s` IS the outer parameter `s`.
 *
 * Containment is by PARENT CHAIN, never by positions: a node's `end` includes the next
 * token (CLAUDE.md "`Node.end` IS NOT THE END OF THE NODE"), so a block's range contains
 * the read right after it.
 *
 * The target's scope is memoized per target `nodeId` within one flow graph (an AST node is
 * a data class and must never be a hash key).
 */
internal class FlowShadowScope {

    private class Entry(val name: String, val scope: Node?)

    private var memoGraph: Any? = null
    private var memo = IntKeyMap<Entry>(64)

    /** Census counters (read by tests): scope ascents run, and assignments refused. */
    var ascents = 0
        private set
    var refused = 0
        private set

    /**
     * True when [target] — a flow assignment's node whose name already matched [root] —
     * writes a block-scoped binding of [root] whose scope does not contain [ref].
     * [graph] identifies the file whose node ids key the memo.
     */
    fun writesOtherBinding(target: Node, root: String, ref: Node, graph: Any?): Boolean {
        val scope = scopeOf(target, root, graph) ?: return false
        var cur: Node? = ref
        while (cur != null) {
            if (cur === scope) return false
            cur = (cur as NodeBase).parent
        }
        refused++
        return true
    }

    private fun scopeOf(target: Node, root: String, graph: Any?): Node? {
        val id = (target as NodeBase).nodeId
        if (id < 0) return ascend(target, root)
        if (graph !== memoGraph) { memo = IntKeyMap(64); memoGraph = graph }
        val e = memo[id]
        if (e != null && e.name == root) return e.scope
        val scope = ascend(target, root)
        memo[id] = Entry(root, scope)
        return scope
    }

    private fun ascend(target: Node, root: String): Node? {
        ascents++
        var cur: Node? = target
        while (cur != null) {
            when (cur) {
                is FunctionDeclaration, is FunctionExpression, is ArrowFunction, is MethodDeclaration,
                is Constructor, is GetAccessor, is SetAccessor, is ClassStaticBlockDeclaration,
                is SourceFile, is ModuleBlock -> return null
                is Block -> if (!isFunctionBody(cur) && statementsBind(cur.statements, root)) return cur
                is SwitchStatement -> for (clause in cur.caseBlock) {
                    val sts = when (clause) {
                        is CaseClause -> clause.statements
                        is DefaultClause -> clause.statements
                        else -> continue
                    }
                    if (statementsBind(sts, root)) return cur
                }
                is ForStatement -> if (listBinds(cur.initializer, root)) return cur
                is ForOfStatement -> if (listBinds(cur.initializer, root)) return cur
                is ForInStatement -> if (listBinds(cur.initializer, root)) return cur
                is CatchClause -> if (cur.variableDeclaration?.let { bindsName(it.name, root) } == true) return cur
                else -> {}
            }
            cur = (cur as NodeBase).parent
        }
        return null
    }

    private fun isFunctionBody(block: Block): Boolean = when ((block as NodeBase).parent) {
        is FunctionDeclaration, is FunctionExpression, is ArrowFunction, is MethodDeclaration,
        is Constructor, is GetAccessor, is SetAccessor, is ClassStaticBlockDeclaration -> true
        else -> false
    }

    private fun statementsBind(statements: List<Statement>, root: String): Boolean =
        statements.any { it is VariableStatement && listBinds(it.declarationList, root) }

    /** A `let` / `const` / `using` list declaring [root] — never `var`. */
    private fun listBinds(init: Node?, root: String): Boolean {
        val l = init as? VariableDeclarationList ?: return false
        if (l.flags == SyntaxKind.VarKeyword) return false
        return l.declarations.any { bindsName(it.name, root) }
    }

    private fun bindsName(n: Node?, root: String): Boolean = when (n) {
        is Identifier -> n.text == root
        is ObjectBindingPattern -> n.elements.any { bindsName(it.name, root) }
        is ArrayBindingPattern -> n.elements.any { (it as? BindingElement)?.let { e -> bindsName(e.name, root) } == true }
        else -> false
    }
}
