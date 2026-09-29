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

    // (CHK.173) G5 S2 — per-file memo of [directScopeNames], keyed by the per-file `nodeId`.
    private var directNamesFile: String? = null
    private var directNamesCache = IntKeyMap<List<String>>(64)

    /**
     * (CHK.173) G5 S2 — the names a BLOCK SCOPE declares directly, for the spine walkers
     * that keep one flat map per function (arith, cpa): the `let` / `const` / `using` leaves
     * of a statement-position [Block]'s own statements (plus a `catch` variable's leaves when
     * the block is a catch body), of EVERY clause of a [SwitchStatement] (the case block is
     * ONE scope — clauses share it), and of a [ForStatement]'s header. Empty for anything
     * else, including a function / accessor / static-block body (the function frame's job)
     * and a file-level block. `var` is never included — it is function-scoped.
     *
     * The walker opens a scope only for the names it holds or that are bound at file /
     * global level, so a block that shadows nothing pays one memo probe.
     */
    fun directScopeNames(scope: Node, fileName: String?): List<String> {
        val id = (scope as NodeBase).nodeId
        if (id < 0) return collectDirectScopeNames(scope)
        if (directNamesFile != fileName) { directNamesCache = IntKeyMap(64); directNamesFile = fileName }
        return directNamesCache[id] ?: collectDirectScopeNames(scope).also { directNamesCache[id] = it }
    }

    companion object {

        /** The identifier leaves of a binding name (an identifier or a destructuring pattern). */
        fun bindingLeaves(name: Node?): List<String> {
            val out = ArrayList<String>(2)
            fun leaves(n: Node?) {
                when (n) {
                    is Identifier -> out.add(n.text)
                    is ObjectBindingPattern -> for (e in n.elements) leaves(e.name)
                    is ArrayBindingPattern -> for (e in n.elements) (e as? BindingElement)?.let { leaves(it.name) }
                    else -> {}
                }
            }
            leaves(name)
            return out
        }

        /** The uncached body of [directScopeNames] (a `for…of` / `for…in` answers its
         *  own let/const binding — cta's loop-body scope; never a slice-1 key). */
        fun collectDirectScopeNames(scope: Node): List<String> {
            var out: ArrayList<String>? = null
            fun leaves(n: Node?) {
                when (n) {
                    is Identifier -> (out ?: ArrayList<String>(2).also { out = it }).add(n.text)
                    is ObjectBindingPattern -> for (e in n.elements) leaves(e.name)
                    is ArrayBindingPattern -> for (e in n.elements) (e as? BindingElement)?.let { leaves(it.name) }
                    else -> {}
                }
            }
            fun list(init: Node?) {
                val l = init as? VariableDeclarationList ?: return
                if (l.flags != SyntaxKind.LetKeyword && l.flags != SyntaxKind.ConstKeyword &&
                    l.flags != SyntaxKind.UsingKeyword && l.flags != SyntaxKind.AwaitUsingKeyword) return
                for (d in l.declarations) leaves(d.name)
            }
            fun statements(sts: List<Statement>) { for (st in sts) if (st is VariableStatement) list(st.declarationList) }
            when (scope) {
                is Block -> {
                    when (val p = (scope as NodeBase).parent) {
                        null, is SourceFile, is FunctionDeclaration, is FunctionExpression, is ArrowFunction,
                        is MethodDeclaration, is Constructor, is GetAccessor, is SetAccessor,
                        is ClassStaticBlockDeclaration -> return emptyList()
                        is CatchClause -> p.variableDeclaration?.let { leaves(it.name) }
                        else -> {}
                    }
                    statements(scope.statements)
                }
                is SwitchStatement -> for (clause in scope.caseBlock) when (clause) {
                    is CaseClause -> statements(clause.statements)
                    is DefaultClause -> statements(clause.statements)
                    else -> {}
                }
                is ForStatement -> list(scope.initializer)
                is ForOfStatement -> list(scope.initializer)
                is ForInStatement -> list(scope.initializer)
                else -> {}
            }
            return out ?: emptyList()
        }

        /**
         * (CHK.173) G5 S2 slice 3 — the `let` / `const` / `using` DECLARATIONS behind
         * [collectDirectScopeNames] for a statement [Block] (never a function body), every
         * clause of a [SwitchStatement], or a [ForStatement] header. The `catch` variable is
         * NOT included (its type is the catch rule's, not an initializer's).
         */
        fun collectDirectScopeDeclarations(scope: Node): List<VariableDeclaration> {
            val out = ArrayList<VariableDeclaration>(2)
            fun list(init: Node?) {
                val l = init as? VariableDeclarationList ?: return
                if (l.flags != SyntaxKind.LetKeyword && l.flags != SyntaxKind.ConstKeyword &&
                    l.flags != SyntaxKind.UsingKeyword && l.flags != SyntaxKind.AwaitUsingKeyword) return
                out.addAll(l.declarations)
            }
            fun statements(sts: List<Statement>) { for (st in sts) if (st is VariableStatement) list(st.declarationList) }
            when (scope) {
                is Block -> when ((scope as NodeBase).parent) {
                    null, is SourceFile, is FunctionDeclaration, is FunctionExpression, is ArrowFunction,
                    is MethodDeclaration, is Constructor, is GetAccessor, is SetAccessor,
                    is ClassStaticBlockDeclaration -> {}
                    else -> statements(scope.statements)
                }
                is SwitchStatement -> for (clause in scope.caseBlock) when (clause) {
                    is CaseClause -> statements(clause.statements)
                    is DefaultClause -> statements(clause.statements)
                    else -> {}
                }
                is ForStatement -> list(scope.initializer)
                else -> {}
            }
            return out
        }

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

        /**
         * (CHK.173) Round A — the binding guard of the identifier-receiver TS1804x arm
         * ([Checker] `emitTs1804xForNullableIdentifierReceiver`). Until G5 S2 lands the
         * cpa frame answers a BLOCK-scoped shadow of a nullable name with the OUTER
         * binding's type, and the arm would turn that wrong type into a confident false
         * positive. True — REFUSE — when the INNERMOST syntactic binding of [name] above
         * [node] is (R1) a `catch` variable, (R2) a named function / class expression's own
         * name or a `function` / `class` / `enum` declaration, or (R3) a binding-pattern
         * leaf of a `var`/`let`/`const` in a block, function body or case clause. A
         * parameter (destructured leaves included), a `for`-header binding, a plain
         * identifier declaration in a statement list and anything at file / namespace
         * level are typed correctly and answer false. Asked only at a site about to fire,
         * so it carries no cache.
         */
        fun nullableReceiverBindingRefused(node: Node, name: String): Boolean {
            var cur: Node? = (node as NodeBase).parent
            var hops = 0
            while (cur != null && cur !is SourceFile && hops++ < 512) {
                if (cur is ModuleBlock || cur is ModuleDeclaration) return false
                when (cur) {
                    is FunctionDeclaration -> if (paramsBind(cur.parameters, name)) return false
                    is ArrowFunction -> if (paramsBind(cur.parameters, name)) return false
                    is MethodDeclaration -> if (paramsBind(cur.parameters, name)) return false
                    is Constructor -> if (paramsBind(cur.parameters, name)) return false
                    is GetAccessor -> if (paramsBind(cur.parameters, name)) return false
                    is SetAccessor -> if (paramsBind(cur.parameters, name)) return false
                    is FunctionExpression -> {
                        if (paramsBind(cur.parameters, name)) return false
                        if (cur.name?.text == name) return true // R2
                    }
                    is ClassExpression -> if (cur.name?.text == name) return true // R2
                    is CatchClause -> {
                        val v = cur.variableDeclaration
                        if (v != null && bindsName(v.name, name)) return true // R1
                    }
                    is ForStatement -> if (listBinds(cur.initializer, name)) return false
                    is ForInStatement -> if (listBinds(cur.initializer, name)) return false
                    is ForOfStatement -> if (listBinds(cur.initializer, name)) return false
                    is Block -> statementsBinding(cur.statements, name)?.let { return it }
                    is SwitchStatement -> {
                        var verdict: Boolean? = null
                        for (clause in cur.caseBlock) {
                            val sts = when (clause) {
                                is CaseClause -> clause.statements
                                is DefaultClause -> clause.statements
                                else -> continue
                            }
                            val v = statementsBinding(sts, name) ?: continue
                            verdict = if (verdict == null) v else (verdict || v)
                        }
                        if (verdict != null) return verdict
                    }
                    else -> {}
                }
                cur = (cur as NodeBase).parent
            }
            return false
        }

        /**
         * (CHK.173) Round B2 (G2) — the OPTIONAL parameter `name?: T` (plain identifier,
         * no initializer, not rest) of the nearest function-like above [node] whose
         * parameter list binds [name], or null. A named function / class expression's own
         * name, a `catch` variable and a namespace body end the ascent with null; a block-
         * scoped shadow is NOT seen here — the caller compares the lexical symbol's
         * declaration with the returned parameter.
         */
        fun optionalParameterBinding(node: Node, name: String): Parameter? {
            var cur: Node? = (node as NodeBase).parent
            var hops = 0
            while (cur != null && cur !is SourceFile && hops++ < 512) {
                if (cur is ModuleBlock || cur is ModuleDeclaration) return null
                val params = when (cur) {
                    is FunctionDeclaration -> cur.parameters
                    is ArrowFunction -> cur.parameters
                    is MethodDeclaration -> cur.parameters
                    is Constructor -> cur.parameters
                    is GetAccessor -> cur.parameters
                    is SetAccessor -> cur.parameters
                    is FunctionExpression -> cur.parameters
                    else -> null
                }
                if (params != null) {
                    val p = params.firstOrNull { bindsName(it.name, name) }
                    if (p != null) {
                        return p.takeIf {
                            it.name is Identifier && it.questionToken && it.initializer == null && !it.dotDotDotToken
                        }
                    }
                    if (cur is FunctionExpression && cur.name?.text == name) return null
                }
                if (cur is ClassExpression && cur.name?.text == name) return null
                if (cur is CatchClause) {
                    val v = cur.variableDeclaration
                    if (v != null && bindsName(v.name, name)) return null
                }
                cur = (cur as NodeBase).parent
            }
            return null
        }

        /**
         * (CHK.173) B5b — the OPTIONAL parameter `name?: T` (plain identifier, no
         * initializer, not rest) that is the INNERMOST syntactic binding of [name] above
         * [node], or null. Unlike [optionalParameterBinding] a block-scoped shadow (`{
         * const name = …; … }`, a `for` header, a `catch` variable, a nested `function`)
         * ends the ascent — the lexical symbol cannot see one (B83.5 leaves it unbound).
         */
        fun innermostOptionalParameter(node: Node, name: String): Parameter? {
            var cur: Node? = (node as NodeBase).parent
            var hops = 0
            while (cur != null && cur !is SourceFile && hops++ < 512) {
                if (cur is ModuleBlock || cur is ModuleDeclaration) return null
                if (name in scopeBindings(cur)) {
                    val params = when (cur) {
                        is FunctionDeclaration -> cur.parameters
                        is ArrowFunction -> cur.parameters
                        is MethodDeclaration -> cur.parameters
                        is Constructor -> cur.parameters
                        is GetAccessor -> cur.parameters
                        is SetAccessor -> cur.parameters
                        is FunctionExpression -> cur.parameters
                        else -> return null
                    }
                    val p = params.firstOrNull { bindsName(it.name, name) } ?: return null
                    return p.takeIf {
                        it.name is Identifier && it.questionToken && it.initializer == null && !it.dotDotDotToken
                    }
                }
                cur = (cur as NodeBase).parent
            }
            return null
        }

        /** A statement list's verdict for [name]: null when it binds nothing (a declaration wins). */
        private fun statementsBinding(statements: List<Statement>, name: String): Boolean? {
            for (st in statements) when (st) {
                is FunctionDeclaration -> if (st.name?.text == name) return true // R2
                is ClassDeclaration -> if (st.name?.text == name) return true // R2
                is EnumDeclaration -> if (st.name.text == name) return true // R2
                else -> {}
            }
            var verdict: Boolean? = null
            for (st in statements) {
                if (st !is VariableStatement) continue
                for (d in st.declarationList.declarations) {
                    val n = d.name
                    if (n is Identifier) { if (n.text == name) verdict = verdict ?: false }
                    else if (bindsName(n, name)) return true // R3
                }
            }
            return verdict
        }

        private fun paramsBind(params: List<Parameter>, name: String): Boolean = params.any { bindsName(it.name, name) }

        private fun listBinds(init: Node?, name: String): Boolean =
            (init as? VariableDeclarationList)?.declarations?.any { bindsName(it.name, name) } == true

        private fun bindsName(n: Node?, name: String): Boolean = when (n) {
            is Identifier -> n.text == name
            is ObjectBindingPattern -> n.elements.any { bindsName(it.name, name) }
            is ArrayBindingPattern -> n.elements.any { (it as? BindingElement)?.let { e -> bindsName(e.name, name) } == true }
            else -> false
        }

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
