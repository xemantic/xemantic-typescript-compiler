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
 * (CHK.173) G5 S1 — the name-lookup guard for a LOCAL VARIABLE that shadows an outer
 * binding but is registered in no walk-scoped table.
 *
 * The identifier ladder ([Checker.getTypeOfIdentifierConventional]) answers a name from
 * the walk tables first (`currentLocalTypes`, the destructured-parameter set) and then
 * from the FILE / GLOBAL tables. A binding the walk never recorded — a `catch` variable,
 * a `for`-header `let`, a block-scoped `let`/`const`, a destructured leaf the recorders
 * missed, an `any`-annotated parameter ([Checker.populateParameterLocalTypes] skips
 * `any`) — therefore resolved to the OUTER declaration of the same name: a wrong type,
 * and on legal code a false positive (tsgo 7.0.2 is silent on every census cell).
 *
 * The guard answers `any` for such a read, which removes the wrong-type answer and can
 * add no row (the INNER type is (CHK.173) S2's job). It is asked only where the outer
 * ladder produced a real (non-`any`) answer, and it is gated on [namesOf] — the names
 * the file binds as a non-file-level variable — so the syntactic ascent runs only for a
 * name that can shadow at all.
 *
 * The ascent answers for the INNERMOST binding of the name between the node and its
 * file: a `function` / `class` / `enum` declaration, or a named function / class
 * expression's own name, makes it answer FALSE, because the scope-space consult in
 * [Checker.getTypeOfIdentifierCore] owns those (returning `any` here would pre-empt it,
 * since that consult keeps an `any` conventional answer). A namespace body ends the
 * ascent (its names are the namespace-exports rung's), as does the file.
 */
internal class LocalShadowGuard(private val sourceFileOf: (String) -> SourceFile?) {

    private val namesByFile = HashMap<String, Set<String>>()
    private var lastFile: String? = null
    private var lastNames: Set<String> = emptySet()

    // Per-file memo of each scope node's bound names (keyed by the per-file `nodeId` —
    // an AST node is a data class and must never be a hash key). Without it every ascent
    // re-scanned every statement of every enclosing block: +3.6% warm on the compiler
    // profile, where [namesOf] admits almost every read (it holds every parameter name).
    private var scopeCacheFile: String? = null
    private var scopeCache = IntKeyMap<Map<String, Int>>(256)

    /** Census counters (read by tests): gate hits and ascents that answered `any`. */
    var ascents = 0
        private set
    var guarded = 0
        private set

    /**
     * True when [id] — read in the file [fileName] — is bound by a local variable-like
     * declaration between itself and the file, i.e. the outer answer is the wrong one.
     */
    fun shadowedByLocalVariable(id: Identifier, fileName: String?): Boolean {
        if (fileName == null) return false
        val names = if (fileName == lastFile) lastNames else {
            val n = namesByFile.getOrPut(fileName) { sourceFileOf(fileName)?.let { collectLocalVariableNames(it) } ?: emptySet() }
            lastFile = fileName; lastNames = n; n
        }
        val name = id.text
        if (name !in names) return false
        ascents++
        if (scopeCacheFile != fileName) { scopeCache = IntKeyMap(256); scopeCacheFile = fileName }
        val bound = innermostBindingIsVariable(id, name, scopeCache)
        if (bound) guarded++
        return bound
    }

    companion object {

        private const val NONE = 0
        private const val VARIABLE = 1
        private const val DECLARATION = 2

        /**
         * Every name [sourceFile] binds as a parameter or as a variable NOT at file level
         * (a block / function-body / `for`-header / `catch` / namespace declaration).
         * Over-approximates freely (a type-level signature's parameter names are in it):
         * it is only the gate, and the ascent re-verifies every hit.
         */
        fun collectLocalVariableNames(sourceFile: SourceFile): Set<String> {
            val out = HashSet<String>()
            val stack = ArrayList<Node>(64)
            val push: (Node) -> Unit = { stack.add(it) }
            forEachChild(sourceFile, push)
            while (stack.isNotEmpty()) {
                val node = stack.removeAt(stack.size - 1)
                when (node) {
                    is Parameter -> addBindingNames(node.name, out)
                    is VariableDeclaration -> {
                        val list = (node as NodeBase).parent
                        val stmt = (list as? NodeBase)?.parent
                        val fileLevel = list is VariableDeclarationList && stmt is VariableStatement &&
                            (stmt as NodeBase).parent is SourceFile
                        if (!fileLevel) addBindingNames(node.name, out)
                    }
                    else -> {}
                }
                forEachChild(node, push)
            }
            return out
        }

        private fun addBindingNames(name: Node?, out: MutableSet<String>) {
            when (name) {
                is Identifier -> out.add(name.text)
                is ObjectBindingPattern -> for (e in name.elements) addBindingNames(e.name, out)
                is ArrayBindingPattern -> for (e in name.elements) (e as? BindingElement)?.let { addBindingNames(it.name, out) }
                else -> {}
            }
        }

        /**
         * (CHK.173) G5 S3 — a function-top declaration with NEITHER an annotation NOR an
         * initializer (`let x;`, `var x;`) shadows an outer `x`, but no recorder writes
         * an annotation string for it, so the frame's legacy string map ([varTypes],
         * a scoped view seeded from the enclosing frame) kept the OUTER declaration's
         * string and the assignment channel reported `x = 1` against it (TS2322 on legal
         * code; tsgo types the local as an evolving `let`). An annotated declaration is
         * left alone because the walk re-records it; one with an initializer too,
         * because nothing here proves its string wrong. A parameter redeclared by `var`
         * keeps the parameter's string (a redeclaration, not a shadow).
         */
        fun dropInheritedUninitializedStrings(
            statements: List<Statement>, paramNames: Set<String>, varTypes: MutableMap<String, String>,
        ) {
            for (st in statements) {
                if (st !is VariableStatement) continue
                for (d in st.declarationList.declarations) {
                    if (d.type != null || d.initializer != null) continue
                    val nm = (d.name as? Identifier)?.text ?: continue
                    if (nm in paramNames) continue
                    if (varTypes.containsKey(nm)) varTypes.remove(nm)
                }
            }
        }

        /** The names one scope level binds, VARIABLE or DECLARATION (a declaration wins). */
        private fun scopeBindings(cur: Node): Map<String, Int> {
            val m = HashMap<String, Int>()
            fun addParams(params: List<Parameter>) { for (p in params) addBinding(p.name, m) }
            fun addStatements(statements: List<Statement>) {
                for (st in statements) if (st is VariableStatement) for (d in st.declarationList.declarations) addBinding(d.name, m)
                for (st in statements) when (st) {
                    is FunctionDeclaration -> st.name?.text?.let { m[it] = DECLARATION }
                    is ClassDeclaration -> st.name?.text?.let { m[it] = DECLARATION }
                    is EnumDeclaration -> m[st.name.text] = DECLARATION
                    else -> {}
                }
            }
            fun addList(init: Node?) { (init as? VariableDeclarationList)?.let { for (d in it.declarations) addBinding(d.name, m) } }
            when (cur) {
                is FunctionDeclaration -> addParams(cur.parameters)
                is FunctionExpression -> { addParams(cur.parameters); cur.name?.text?.let { m[it] = DECLARATION } }
                is ArrowFunction -> addParams(cur.parameters)
                is MethodDeclaration -> addParams(cur.parameters)
                is Constructor -> addParams(cur.parameters)
                is GetAccessor -> addParams(cur.parameters)
                is SetAccessor -> addParams(cur.parameters)
                is ClassExpression -> cur.name?.text?.let { m[it] = DECLARATION }
                is CatchClause -> cur.variableDeclaration?.let { addBinding(it.name, m) }
                is ForStatement -> addList(cur.initializer)
                is ForInStatement -> addList(cur.initializer)
                is ForOfStatement -> addList(cur.initializer)
                is Block -> addStatements(cur.statements)
                is SwitchStatement -> {
                    val decls = HashSet<String>()
                    for (clause in cur.caseBlock) {
                        val sts = when (clause) {
                            is CaseClause -> clause.statements
                            is DefaultClause -> clause.statements
                            else -> continue
                        }
                        val sub = HashMap<String, Int>()
                        for (st in sts) if (st is VariableStatement) for (d in st.declarationList.declarations) addBinding(d.name, sub)
                        for (st in sts) when (st) {
                            is FunctionDeclaration -> st.name?.text?.let { decls.add(it) }
                            is ClassDeclaration -> st.name?.text?.let { decls.add(it) }
                            is EnumDeclaration -> decls.add(st.name.text)
                            else -> {}
                        }
                        m.putAll(sub)
                    }
                    for (d in decls) m[d] = DECLARATION
                }
                else -> {}
            }
            return if (m.isEmpty()) emptyMap() else m
        }

        private fun addBinding(name: Node?, out: MutableMap<String, Int>) {
            when (name) {
                is Identifier -> out.putIfAbsentCompat(name.text, VARIABLE)
                is ObjectBindingPattern -> for (e in name.elements) addBinding(e.name, out)
                is ArrayBindingPattern -> for (e in name.elements) (e as? BindingElement)?.let { addBinding(it.name, out) }
                else -> {}
            }
        }

        private fun MutableMap<String, Int>.putIfAbsentCompat(k: String, v: Int) { if (k !in this) this[k] = v }

        /** The ascent. See the class KDoc for what ends it and what it answers. */
        fun innermostBindingIsVariable(node: Node, name: String, cache: IntKeyMap<Map<String, Int>>? = null): Boolean {
            var cur: Node? = (node as NodeBase).parent
            var hops = 0
            while (cur != null && cur !is SourceFile && hops++ < 512) {
                if (cur is ModuleBlock || cur is ModuleDeclaration) return false
                val id = (cur as NodeBase).nodeId
                val bindings = if (cache != null && id >= 0) {
                    cache[id] ?: scopeBindings(cur).also { cache[id] = it }
                } else scopeBindings(cur)
                val found = bindings[name] ?: NONE
                if (found == VARIABLE) return true
                if (found == DECLARATION) return false
                cur = (cur as NodeBase).parent
            }
            return false
        }
    }
}
