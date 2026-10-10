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

package com.xemantic.typescript.goport.lower

import com.xemantic.typescript.goport.ir.IrPackage
import com.xemantic.typescript.goport.ir.Node
import com.xemantic.typescript.goport.ir.bool
import com.xemantic.typescript.goport.ir.int
import com.xemantic.typescript.goport.ir.list
import com.xemantic.typescript.goport.ir.obj
import com.xemantic.typescript.goport.ir.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Struct types whose function-local values may be POOLED ([Program.computePooledLocals], docs/goport-perf.md
 * § 12): the checker builds every relation/union/instantiation cache key in a `var b keyBuilder` local — a
 * `keyBuilder`, its `xxh3.Hasher` and the Hasher's byte buffer per key on the JVM, ~8% of a check's allocation,
 * where Go keeps all of it on the stack.
 */
val POOLED_LOCAL_CANDIDATES = setOf(
    "github.com/microsoft/typescript-go/internal/checker.keyBuilder",
)

/**
 * Shim struct types whose methods never retain their receiver or an argument and whose results never alias
 * the receiver (hand-written; checked by reading the shim): a call on one is not an escape.
 * `xxh3.Hasher`: `Write` copies the bytes into its own buffer, `Sum128`/`Sum64` answer fresh values.
 */
val NON_RETAINING_SHIM_TYPES = setOf(
    "github.com/zeebo/xxh3.Hasher",
)

/** What the escape proof needs from the IR (an interface so the pins can hand it a tiny synthetic IR). */
interface EscapeIr {
    /** The object key of object [id] of [p] (a function, method, field, …), or null. */
    fun objKey(p: IrPackage, id: Int): String?

    /** The key of the named type [typeId] denotes (aliases resolved), or null. */
    fun named(p: IrPackage, typeId: Int?): String?

    /** The key of the named type a POINTER type [typeId] points to, or null. */
    fun pointee(p: IrPackage, typeId: Int?): String?
}

/**
 * The escape proof behind pooled locals. A value may be pooled when Go could have kept it on the stack AND
 * no pointer into it survives its function: every occurrence of the local is one of
 *
 * - the receiver of a pointer method whose receiver is NON-RETAINING (below), `b.writeInt(n)`;
 * - a field select `b.h` that is itself the receiver of a method of a [NON_RETAINING_SHIM_TYPES] type or of a
 *   non-retaining method, or the operand of an `&` passed straight to a non-retaining parameter
 *   (`hashWrite32(&b.h, v)`);
 * - `&b` passed straight to a non-retaining parameter (`c.writeFlowCacheKey(&b, …)`);
 *
 * and an occurrence inside a function literal is allowed only when that literal is LOCAL: assigned to a local
 * variable that is otherwise only declared (no value) and called. A pointer parameter (or receiver) of a
 * candidate or non-retaining shim type is NON-RETAINING when its uses obey the same rules, where passing the
 * pointer itself straight to a non-retaining parameter also counts (a fixpoint over the whole run, starting
 * from "all"). The function declaring the local must be a top-level declaration whose body holds no `go` and no
 * `defer` (a deferred call runs after the generated `finally` released the value), and the local must be a
 * `var b T` with no value at the top level of that body.
 *
 * Everything else — a return, a store, a copy, a comparison, an interface conversion, a call through a
 * function value or an interface, a variadic slot, a parameter of a function the run does not declare — is an
 * escape, and the local keeps today's lowering.
 */
class EscapeAnalysis(
    private val ir: EscapeIr,
    private val packages: List<IrPackage>,
    private val candidates: Set<String> = POOLED_LOCAL_CANDIDATES,
    private val nonRetainingShim: Set<String> = NON_RETAINING_SHIM_TYPES,
) {
    private class Decl(val p: IrPackage, val d: Node, val key: String)

    /** Top-level functions and methods of the run, by object key. */
    private val decls = HashMap<String, Decl>()

    /** Parameters (`key#i`, the receiver `key#-1`) of pointer-to-tracked type, with their object id. */
    private val ptrParams = HashMap<String, Pair<Decl, Int>>()

    /** The non-retaining subset of [ptrParams] (the fixpoint). */
    val nonRetaining = HashSet<String>()

    /** Pooled locals per package path (object ids), and why each candidate local was refused. */
    val pooled = HashMap<String, MutableSet<Int>>()
    val refusals = ArrayList<String>()

    private val tracked = candidates + nonRetainingShim

    init {
        for (p in packages) for (f in p.files) for (d in f.list("decls")) {
            if (d.str("k") != "FuncDecl") continue
            val key = d.int("obj")?.let { ir.objKey(p, it) } ?: continue
            val decl = Decl(p, d, key)
            decls[key] = decl
            d.obj("recv")?.list("list")?.firstOrNull()?.let { rf ->
                val r = rf.list("names").firstOrNull()?.int("obj")
                if (r != null && ir.pointee(p, rf.obj("type")?.int("t")) in tracked) ptrParams["$key#-1"] = decl to r
            }
            val fields = d.obj("type")?.obj("params")?.list("list") ?: emptyList()
            var i = 0
            fields.forEach { fld ->
                val names = fld.list("names")
                val variadic = fld.obj("type")?.str("k") == "Ellipsis"
                if (names.isEmpty()) { i++; return@forEach }
                for (n in names) {
                    val o = n.int("obj")
                    if (!variadic && o != null && ir.pointee(p, fld.obj("type")?.int("t")) in tracked) ptrParams["$key#$i"] = decl to o
                    i++
                }
            }
        }
        nonRetaining += ptrParams.keys
        var changed = true
        while (changed) {
            changed = false
            for (k in nonRetaining.toList()) {
                val (decl, o) = ptrParams.getValue(k)
                val body = decl.d.obj("body")
                val why = if (body == null) "no body" else violation(decl.p, body, o, pointer = true)
                if (why != null) {
                    nonRetaining.remove(k)
                    changed = true
                }
            }
        }
        for (decl in decls.values) {
            val body = decl.d.obj("body") ?: continue
            val top = body.list("list").toSet()
            walk(body) { n ->
                if (n.str("k") == "ValueSpec") for (name in n.list("names")) {
                    val o = name.int("obj") ?: continue
                    val t = ir.named(decl.p, decl.p.objects.getOrNull(o)?.int("t")) ?: continue
                    if (t !in candidates) continue
                    val why = localRefusal(decl, body, top, n, o)
                    if (why == null) pooled.getOrPut(decl.p.path) { HashSet() } += o
                    else refusals += "${decl.key} ${name.str("name")}: $why"
                }
                true
            }
        }
    }

    private fun localRefusal(decl: Decl, body: Node, top: Set<Node>, spec: Node, o: Int): String? {
        if (spec.list("values").isNotEmpty()) return "declared with a value"
        if (spec.list("names").size != 1) return "declared beside another name"
        if (top.none { s -> s.str("k") == "DeclStmt" && s.obj("decl")?.list("specs")?.any { it === spec } == true }) return "not declared at the top level of the body"
        var goOrDefer: String? = null
        walk(body) { n -> if (n.str("k") == "GoStmt" || n.str("k") == "DeferStmt") goOrDefer = n.str("k"); goOrDefer == null }
        if (goOrDefer != null) return "the function has a $goOrDefer"
        return violation(decl.p, body, o, pointer = false)
    }

    /** The first escaping use of object [o] in [body], or null. [pointer]: [o] is a pointer (a parameter), else a struct value. */
    fun violation(p: IrPackage, body: Node, o: Int, pointer: Boolean): String? {
        var why: String? = null
        val chain = ArrayList<Pair<Node, String>>()
        fun visit(n: Node) {
            if (why != null) return
            if (n.str("k") == "Ident" && n.int("obj") == o) {
                why = occurrence(p, body, n, chain, pointer)
                return
            }
            for ((k, v) in n) {
                when (v) {
                    is JsonObject -> { chain += n to k; visit(v); chain.removeAt(chain.size - 1) }
                    is JsonArray -> for (e in v) if (e is JsonObject) { chain += n to k; visit(e); chain.removeAt(chain.size - 1) }
                    else -> {}
                }
            }
        }
        visit(body)
        return why
    }

    private fun occurrence(p: IrPackage, body: Node, id: Node, chain: List<Pair<Node, String>>, pointer: Boolean): String? {
        // Inside a function literal: only a LOCAL one (assigned to a local that is only declared and called).
        for (i in chain.indices) {
            val (n, _) = chain[i]
            if (n.str("k") == "FuncLit" && !localFuncLit(body, n, chain.getOrNull(i - 1))) return "captured by a function literal that may escape"
        }
        val (parent, key) = chain.lastOrNull() ?: return "bare"
        if (id.bool("def")) return if (!pointer && parent.str("k") == "ValueSpec" && key == "names") null else "redefined"
        when (parent.str("k")) {
            "SelectorExpr" -> if (key == "x") {
                when (parent.str("selk")) {
                    "method" -> return if (parent.bool("callee") && nonRetainingMethod(p, parent)) null else "a method value or a retaining method"
                    "field" -> {
                        val (q, qk) = chain.getOrNull(chain.size - 2) ?: return "a field read"
                        return when {
                            q.str("k") == "SelectorExpr" && qk == "x" && q.str("selk") == "method" && q.bool("callee") && nonRetainingMethod(p, q) -> null
                            q.str("k") == "UnaryExpr" && q.str("op") == "&" && passedToNonRetaining(p, q, chain.getOrNull(chain.size - 3)) -> null
                            else -> "a field read or stored"
                        }
                    }
                }
            }
            "UnaryExpr" -> if (!pointer && parent.str("op") == "&" && passedToNonRetaining(p, parent, chain.getOrNull(chain.size - 2))) return null
            "CallExpr" -> if (pointer && key == "args" && passedToNonRetaining(p, id, parent to key)) return null
        }
        return "escapes through a ${parent.str("k")}.$key"
    }

    /** A method call on a value whose receiver is non-retaining (or a method of a non-retaining shim type). */
    private fun nonRetainingMethod(p: IrPackage, sel: Node): Boolean {
        val m = sel.obj("sel")?.int("obj")?.let { ir.objKey(p, it) } ?: return false
        return m.substringBeforeLast('.') in nonRetainingShim || "$m#-1" in nonRetaining
    }

    /** [arg] is an argument of the call [parent] (an ancestor link) and its parameter is non-retaining. */
    private fun passedToNonRetaining(p: IrPackage, arg: Node, parent: Pair<Node, String>?): Boolean {
        val (call, key) = parent ?: return false
        if (call.str("k") != "CallExpr" || key != "args") return false
        if (call.str("call") != "func" && call.str("call") != "method") return false
        if (call.bool("ellipsis")) return false
        val i = call.list("args").indexOfFirst { it === arg }
        if (i < 0) return false
        var f = call.obj("fun") ?: return false
        while (f.str("k") == "ParenExpr" || f.str("k") == "IndexExpr" || f.str("k") == "IndexListExpr") f = f.obj("x") ?: return false
        val callee = when (f.str("k")) {
            "Ident" -> f.int("obj")?.let { ir.objKey(p, it) }
            "SelectorExpr" -> f.obj("sel")?.int("obj")?.let { ir.objKey(p, it) }
            else -> null
        } ?: return false
        return "$callee#$i" in nonRetaining
    }

    /**
     * A function literal [lit] (reached from [parent]) is LOCAL when it is the whole right side of an assignment
     * to a local variable that the function otherwise only declares (`var f func(…)`) and calls.
     */
    private fun localFuncLit(body: Node, lit: Node, parent: Pair<Node, String>?): Boolean {
        val (s, key) = parent ?: return false
        if (s.str("k") != "AssignStmt" || key != "rhs" || s.list("rhs").size != 1 || s.list("lhs").size != 1) return false
        val lhs = s.list("lhs")[0]
        if (lhs.str("k") != "Ident" || lhs.bool("blank")) return false
        val f = lhs.int("obj") ?: return false
        var ok = true
        val chain = ArrayList<Pair<Node, String>>()
        fun visit(n: Node) {
            if (!ok) return
            if (n.str("k") == "Ident" && n.int("obj") == f) {
                val (q, qk) = chain.lastOrNull() ?: run { ok = false; return }
                ok = n === lhs ||
                    (q.str("k") == "ValueSpec" && qk == "names" && q.list("values").isEmpty()) ||
                    (q.str("k") == "CallExpr" && qk == "fun")
                return
            }
            for ((k, v) in n) {
                when (v) {
                    is JsonObject -> { chain += n to k; visit(v); chain.removeAt(chain.size - 1) }
                    is JsonArray -> for (e in v) if (e is JsonObject) { chain += n to k; visit(e); chain.removeAt(chain.size - 1) }
                    else -> {}
                }
            }
        }
        visit(body)
        return ok
    }

    private fun walk(n: Any?, visit: (Node) -> Boolean) = Program.walk(n, visit)
}
