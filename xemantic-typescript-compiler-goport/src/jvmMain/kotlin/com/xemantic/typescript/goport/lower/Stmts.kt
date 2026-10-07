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

import com.xemantic.typescript.goport.emit.CodeWriter
import com.xemantic.typescript.goport.emit.Ex
import com.xemantic.typescript.goport.emit.Ex.Companion.PRIMARY
import com.xemantic.typescript.goport.ir.Node
import com.xemantic.typescript.goport.ir.bool
import com.xemantic.typescript.goport.ir.int
import com.xemantic.typescript.goport.ir.k
import com.xemantic.typescript.goport.ir.list
import com.xemantic.typescript.goport.ir.nullableList
import com.xemantic.typescript.goport.ir.obj
import com.xemantic.typescript.goport.ir.reqObj
import com.xemantic.typescript.goport.ir.str
import com.xemantic.typescript.goport.ir.t
import com.xemantic.typescript.goport.lower.TypeMapper.Rep
import com.xemantic.typescript.goport.types.ArrayType
import com.xemantic.typescript.goport.types.MapType
import com.xemantic.typescript.goport.types.PointerType
import com.xemantic.typescript.goport.types.SignatureType
import com.xemantic.typescript.goport.types.StructType
import com.xemantic.typescript.goport.types.TupleType

/** Statement lowering (docs/goport-ir.md § 5; control flow per docs/goport-design.md § 4). */
class Lowering(fn: FnCtx) : CallLowering(fn) {

    val w: CodeWriter get() = fn.w

    // ------------------------------------------------------------------ bodies

    /** Lowers a function body whose frame (results) is already pushed. */
    fun body(stmts: List<Node>) {
        for (s in stmts) stmt(s)
    }

    /** Declares the named results (zero-initialized) of the current frame. */
    fun declareNamedResults() {
        val named = fn.frame.namedResults ?: return
        for (id in named) {
            val n = fn.declare(id)
            val t = pc.obj(id).int("t")!!
            if (id in fn.boxed) w.line("val $n: GoBox<${tm.kt(t)}> = GoBox(${tm.zero(t)})")
            else w.line("var $n: ${tm.kt(t)} = ${tm.zero(t)}")
        }
    }

    /** `fun(params): R { body }` for a func literal. */
    override fun funcLit(e: Node): Ex {
        val ft = e.reqObj("type")
        val sig = types.unalias(ty(e)) as SignatureType
        val params = ft.obj("params")?.list("list") ?: emptyList()
        val decl = paramDecls(params, sig)
        val named = namedResultObjs(ft)
        val outer = fn.w
        val inner = CodeWriter(outer.depth + 1)
        fn.w = inner
        fn.frames.addLast(Frame(sig.results.map { it.t }, named))
        try {
            copyInParams(params)
            declareNamedResults()
            withDefersIfNeeded(e.reqObj("body"), sig.results.map { it.t }) { body(e.reqObj("body").list("list")) }
        } finally {
            fn.frames.removeLast()
            fn.w = outer
        }
        val indent = "    ".repeat(outer.depth)
        return Ex.primary("fun(${decl.joinToString(", ")})${tm.returns(sig.results.map { it.t })} {\n$inner$indent}")
    }

    /** Parameter declarations; a mutated/address-taken param is copied into a local (see [copyInParams]). */
    fun paramDecls(params: List<Node>, sig: SignatureType): List<String> {
        val out = ArrayList<String>()
        var i = 0
        for (f in params) {
            val names = f.list("names")
            if (names.isEmpty()) {
                out += "${fn.fresh("p")}: ${tm.kt(sig.params[i].t)}"
                i++
                continue
            }
            for (n in names) {
                val pt = sig.params[i].t
                val id = n.int("obj")
                if (id == null || n.str("name") == "_") {
                    out += "${fn.fresh("unused")}: ${tm.kt(pt)}"
                } else {
                    val o = pc.obj(id)
                    val name = fn.declare(id)
                    if (i == fn.windowParamIdx) {
                        // A window overload's string parameter: three parameters, read as a view.
                        val b = fn.fresh("${name}_b")
                        val vo = fn.fresh("${name}_o")
                        val vn = fn.fresh("${name}_n")
                        out += "$b: String"
                        out += "$vo: Int"
                        out += "$vn: Int"
                        fn.views[id] = View(b, vo, vn)
                    } else if (i in fn.nonNullParamIdx) {
                        fn.nonNullFnParams += id
                        // The UNDERLYING signature: a named func type is a nullable typealias (`Visitor`).
                        out += "$name: ${tm.kt(types.under(pt).id).removeSuffix("?")}"
                    } else if (o.bool("mut") || id in fn.boxed) {
                        val pn = fn.fresh("${name}_")
                        paramCopies += Triple(id, pn, pt)
                        out += "$pn: ${tm.kt(pt)}"
                    } else out += "$name: ${tm.kt(pt)}"
                }
                i++
            }
        }
        return out
    }

    private val paramCopies = ArrayList<Triple<Int, String, Int>>()

    /** Emits `var p = p_` for every mutated parameter declared by the last [paramDecls]. */
    fun copyInParams(params: List<Node>) {
        val ids = params.flatMap { f -> f.list("names").mapNotNull { it.int("obj") } }.toSet()
        val mine = paramCopies.filter { it.first in ids }
        paramCopies.removeAll(mine.toSet())
        for ((id, pn, t) in mine) {
            val name = fn.nameOf(id)!!
            if (id in fn.boxed) w.line("val $name: GoBox<${tm.kt(t)}> = GoBox($pn)")
            else w.line("var $name: ${tm.kt(t)} = $pn")
        }
    }

    fun namedResultObjs(ft: Node): List<Int>? {
        val res = ft.obj("results")?.list("list") ?: return null
        val ids = res.flatMap { f -> f.list("names").mapNotNull { it.int("obj") } }
        return ids.ifEmpty { null }
    }

    /**
     * Wraps [content] in `withDefers` when [bodyNode] holds a `defer` of THIS function (not of a
     * nested func literal).
     */
    fun withDefersIfNeeded(bodyNode: Node, results: List<Int>, content: () -> Unit) {
        if (!hasOwnDefer(bodyNode)) {
            content()
            ensureTerminated(bodyNode, results)
            return
        }
        if (fn.frame.namedResults != null) refuse("defer-named-results")
        val frameName = fn.fresh("df")
        fn.frame.deferFrame = frameName
        val zero = when (results.size) {
            0 -> "Unit"
            1 -> tm.zero(results[0])
            else -> "${tm.tupleKt(results)}(${results.joinToString(", ") { tm.zero(it) }})"
        }
        val prefix = if (results.isEmpty()) "" else "return "
        w.line("${prefix}withDefers({ $zero }) { $frameName ->")
        w.indent {
            content()
            ensureTerminated(bodyNode, results)
        }
        w.line("}")
    }

    private fun ensureTerminated(bodyNode: Node, results: List<Int>) {
        if (results.isEmpty()) return
        val last = bodyNode.list("list").lastOrNull()
        if (last != null && last.k == "ReturnStmt") return
        if (last != null && last.k == "ExprStmt" && last.reqObj("x").str("builtin") == "panic") return
        w.line("goUnreachable()")
    }

    fun hasOwnDefer(body: Node): Boolean {
        var found = false
        fun walk(n: Any?) {
            when (n) {
                is kotlinx.serialization.json.JsonObject -> {
                    if (found) return
                    if (n.str("k") == "FuncLit") return
                    if (n.str("k") == "DeferStmt") {
                        found = true
                        return
                    }
                    for (v in n.values) walk(v)
                }
                is kotlinx.serialization.json.JsonArray -> for (v in n) walk(v)
                else -> {}
            }
        }
        walk(body)
        return found
    }

    // ------------------------------------------------------------------ statements

    fun stmt(s: Node, goLabel: String? = null) {
        when (s.k) {
            "BlockStmt" -> body(s.list("list"))
            "ExprStmt" -> exprStmt(s.reqObj("x"))
            "AssignStmt" -> assign(s)
            "IncDecStmt" -> incDec(s)
            "DeclStmt" -> declStmt(s.reqObj("decl"))
            "ReturnStmt" -> returnStmt(s)
            "IfStmt" -> ifStmt(s)
            "ForStmt" -> forStmt(s, goLabel)
            "RangeStmt" -> rangeStmt(s, goLabel)
            "SwitchStmt" -> switchStmt(s, goLabel)
            "TypeSwitchStmt" -> typeSwitchStmt(s, goLabel)
            "LabeledStmt" -> stmt(s.reqObj("stmt"), s.reqObj("label").str("name"))
            "BranchStmt" -> branch(s)
            "EmptyStmt" -> {}
            "DeferStmt" -> deferStmt(s)
            "GoStmt" -> refuse("go")
            "SelectStmt" -> refuse("select")
            "SendStmt" -> refuse("chan")
            else -> refuse("stmt", s.k)
        }
    }

    private fun exprStmt(x: Node) {
        val v = lower(x)
        w.line(v.code)
    }

    // ---- assignment

    private fun assign(s: Node) {
        val tok = s.str("tok")!!
        val lhs = s.list("lhs")
        val rhs = s.list("rhs")
        if (tok != "=" && tok != ":=") return opAssign(lhs.single(), tok.dropLast(1), rhs.single())
        val define = tok == ":="
        if (define && lhs.size == 1 && rhs.size == 1 && lhs[0].k == "Ident" && lhs[0].bool("def") &&
            lhs[0].int("obj")?.let { it in viewCandidates() } == true
        ) return declareView(lhs[0].int("obj")!!, rhs[0]) { w.line(it) }
        if (lhs.size == rhs.size) {
            if (lhs.size == 1) return single(lhs[0], flow(rhs[0]), define)
            // Parallel assignment: every right side is evaluated before any store.
            val temps = rhs.map { r ->
                val n = fn.fresh("t")
                w.line("val $n = ${flow(r).code}")
                n
            }
            lhs.forEachIndexed { i, l -> single(l, Ex.primary(temps[i]), define) }
            return
        }
        val r = rhs.single()
        multi(lhs, r, define)
    }

    /** `a, b := f()` / comma-ok forms. */
    private fun multi(lhs: List<Node>, r: Node, define: Boolean) {
        val t = fn.fresh("t")
        if (r.bool("commaOk")) {
            when (r.k) {
                "IndexExpr" -> {
                    // One hash probe and no tuple: `val t = m.probe(k)`; `v` reads it, `ok` tests it.
                    val mx = r.reqObj("x")
                    val m = raw(mx)
                    val mt = types.under(types.core(ty(mx))) as? MapType ?: refuse("commaok-map-type")
                    w.line("val $t = ${m.at(PRIMARY)}.probe(${flow(r.reqObj("index")).code})")
                    val impls = r.nullableList("implTuple")
                    lhs.forEachIndexed { i, l ->
                        if (l.bool("blank")) return@forEachIndexed
                        val v = when {
                            impls.getOrNull(i)?.str("k") == "nil" -> Ex.primary(tm.zero(impls[i]!!.int("to")!!))
                            // Go copies a struct value out of the map (as the map range below does).
                            i == 0 -> Ex.primary("goProbeValue<${tm.kt(mt.elem)}>($t) { ${tm.zero(mt.elem)} }" +
                                (if (tm.isStructValue(mt.elem) && tm.hasGoCopy(mt.elem) && tm.kt(mt.elem) != "Unit") ".goCopy()" else ""))
                            else -> Ex("$t !== GoMapAbsent", Ex.EQ)
                        }
                        single(l, v, define)
                    }
                    return
                }
                "TypeAssertExpr" -> {
                    val x = lower(r.reqObj("x"))
                    val target = r.t?.let { (types.unalias(it) as? TupleType)?.elems?.get(0)?.t } ?: refuse("commaok-type")
                    val v = fn.fresh("x")
                    w.line("val $v = ${x.code}")
                    val check = "$v is ${isCheck(target)}"
                    val cast = if (tm.boxOf(target) != null) "($v as ${castTarget(target)}).value" else "$v as ${castTarget(target)}"
                    w.line("val $t = if ($check) Tuple2($cast, true) else Tuple2(${tm.zero(target)}, false)")
                }
                "ParenExpr" -> return multi(lhs, r.reqObj("x"), define)
                else -> refuse("commaok", r.k)
            }
        } else {
            w.line("val $t = ${lower(r).code}")
        }
        val impls = r.nullableList("implTuple")
        lhs.forEachIndexed { i, l ->
            val comp = listOf("first", "second", "third", "fourth", "fifth")[i]
            if (l.bool("blank")) return@forEachIndexed
            val ex = Ex.primary("$t.$comp")
            val v = if (impls.getOrNull(i)?.str("k") == "nil") Ex.primary(tm.zero(impls[i]!!.int("to")!!)) else ex
            single(l, v, define)
        }
    }

    /** Stores [value] (already lowered with its flow) into [l]; declares when `:=` defines it. */
    private fun single(l: Node, value: Ex, define: Boolean) {
        if (l.bool("blank") || (l.k == "Ident" && l.str("name") == "_")) {
            if (value.code.endsWith(")")) w.line(value.code)
            return
        }
        if (define && l.k == "Ident" && l.bool("def")) {
            val id = l.int("obj")!!
            declareLocal(id, value.code)
            return
        }
        store(l, value)
    }

    fun declareLocal(id: Int, init: String?) {
        val o = pc.obj(id)
        val t = o.int("t")!!
        val n = fn.declare(id)
        val kt = tm.kt(t)
        val v = init ?: tm.zero(t)
        when {
            id in fn.boxed -> w.line("val $n: GoBox<$kt> = GoBox($v)")
            o.bool("mut") && !(tm.isStructValue(t) && o.bool("addr")) -> w.line("var $n: $kt = $v")
            else -> w.line("val $n: $kt = $v")
        }
    }

    /** The assignable Kotlin expression of [l]. */
    private fun store(l: Node, value: Ex) {
        when (l.k) {
            "ParenExpr" -> store(l.reqObj("x"), value)
            "Ident" -> {
                val id = l.int("obj")!!
                val o = pc.obj(id)
                if (o.bool("local") && tm.isStructValue(o.int("t")!!) && o.bool("addr")) {
                    w.line("${lower(l).code}.goSet(${value.code})")
                } else {
                    w.line("${lower(l).code} = ${value.code}")
                }
            }
            "SelectorExpr", "IndexExpr" -> w.line("${lower(l).code} = ${value.code}")
            "StarExpr" -> {
                val p = l.reqObj("x")
                val pt = types.under(ty(p)) as PointerType
                if (types.under(pt.elem) is StructType) w.line("${nn(lower(p)).code}.goSet(${value.code})")
                else if (types.under(pt.elem) is ArrayType) w.line("goCopy(${nn(lower(p)).at(Ex.PRIMARY)}.slice(), ${value.at(Ex.PRIMARY)}.slice())")
                else w.line("${lower(l).code} = ${value.code}")
            }
            else -> refuse("assign-target", l.k)
        }
    }

    private fun opAssign(l: Node, op: String, r: Node) {
        val t = ty(l)
        val rep = tm.repOf(t)
        val simple = !tm.isValueClass(t) && rep != null && tm.kindOf(types.basic(t)!!) in PLAIN_KINDS
        if (simple && op in setOf("+", "-", "*") && (rep == Rep.INT || rep == Rep.LONG || rep == Rep.DOUBLE || (rep == Rep.STRING && op == "+"))) {
            w.line("${lower(l).code} $op= ${raw(r).at(Ex.ADD + 1)}")
            return
        }
        val cur = raw(l)
        val v = wrap(arith(op, cur, raw(r), t, r), t)
        store(l, v)
    }

    private fun incDec(s: Node) {
        val x = s.reqObj("x")
        val t = ty(x)
        val op = if (s.str("tok") == "++") "+" else "-"
        val rep = tm.repOf(t)
        if (!tm.isValueClass(t) && rep != null && tm.kindOf(types.basic(t)!!) in PLAIN_KINDS && x.k == "Ident") {
            w.line("${lower(x).code}${s.str("tok")}")
            return
        }
        val one = when (rep) {
            Rep.LONG -> "1L"
            Rep.UINT -> "1u"
            Rep.ULONG -> "1uL"
            Rep.DOUBLE -> "1.0"
            Rep.FLOAT -> "1.0f"
            else -> "1"
        }
        store(x, wrap(arith(op, raw(x), Ex.primary(one), t, null), t))
    }

    // ---- declarations inside functions

    private fun declStmt(d: Node) {
        when (d.str("tok")) {
            "var" -> for (spec in d.list("specs")) {
                val names = spec.list("names")
                val values = spec.list("values")
                if (values.size == 1 && names.size > 1) {
                    multi(names, values[0], true)
                    continue
                }
                if (names.size == 1 && values.size == 1 && names[0].int("obj")?.let { it in viewCandidates() } == true) {
                    declareView(names[0].int("obj")!!, values[0]) { w.line(it) }
                    continue
                }
                names.forEachIndexed { i, n ->
                    if (n.str("name") == "_") {
                        values.getOrNull(i)?.let { w.line(flow(it).code) }
                        return@forEachIndexed
                    }
                    declareLocal(n.int("obj")!!, values.getOrNull(i)?.let { flow(it).code })
                }
            }
            "const" -> {} // constants are inlined from their exact values at every use
            "type" -> {} // hoisted to the package top level (Program.localTypeQnames)
            else -> refuse("decl", d.str("tok") ?: "?")
        }
    }

    // ---- return

    private fun returnStmt(s: Node) {
        val f = fn.frame
        f.rangeReturn?.let { (done, ret) ->
            // Inside a range-over-func body: record the result, stop the iterator.
            val vals: List<String> = if (s.bool("bare")) (f.namedResults ?: refuse("bare-return")).map { lower(identOf(it)).code }
                else s.list("results").let { rs ->
                    if (rs.size == 1 && f.results.size > 1) refuse("range-func-return-tuple") else rs.map { flow(it).code }
                }
            if (vals.isNotEmpty()) w.line("$ret = ${tupleOf(vals, f.results)}")
            w.line("$done = true")
            w.line("return false")
            return
        }
        if (s.bool("bare")) {
            val named = f.namedResults ?: refuse("bare-return")
            val vals = named.map { lower(identOf(it)).code }
            w.line("return " + tupleOf(vals, f.results))
            return
        }
        val rs = s.list("results")
        when {
            rs.isEmpty() -> w.line("return")
            rs.size == 1 && f.results.size > 1 -> {
                // return g() — a tuple-valued call.
                val impls = rs[0].nullableList("implTuple")
                if (impls.all { it == null }) w.line("return ${lower(rs[0]).code}")
                else {
                    val t = fn.fresh("t")
                    w.line("val $t = ${lower(rs[0]).code}")
                    val comps = f.results.indices.map { i ->
                        val im = impls.getOrNull(i)
                        if (im?.str("k") == "nil") tm.zero(im.int("to")!!) else "$t.${COMPS[i]}"
                    }
                    w.line("return " + tupleOf(comps, f.results))
                }
            }
            else -> w.line("return " + tupleOf(rs.map { flow(it).code }, f.results))
        }
    }

    private fun tupleOf(vals: List<String>, results: List<Int>): String =
        if (vals.size == 1) vals[0] else "${tm.tupleKt(results)}(${vals.joinToString(", ")})"

    /** A synthetic Ident node for object [id] (to reuse identifier lowering). */
    private fun identOf(id: Int): Node = kotlinx.serialization.json.JsonObject(
        mapOf(
            "k" to kotlinx.serialization.json.JsonPrimitive("Ident"),
            "obj" to kotlinx.serialization.json.JsonPrimitive(id),
            "t" to kotlinx.serialization.json.JsonPrimitive(pc.obj(id).int("t")!!),
            "m" to kotlinx.serialization.json.JsonPrimitive("variable"),
            "name" to kotlinx.serialization.json.JsonPrimitive(pc.obj(id).str("name")),
        )
    )

    // ---- if

    private fun ifStmt(s: Node) {
        s.obj("init")?.let { stmt(it) }
        w.line("if (${lower(s.reqObj("cond")).code}) {")
        w.indent { body(s.reqObj("body").list("list")) }
        var els = s.obj("else")
        while (els != null) {
            if (els.k == "IfStmt" && els.obj("init") == null) {
                w.line("} else if (${lower(els.reqObj("cond")).code}) {")
                val b = els.reqObj("body").list("list")
                w.indent { body(b) }
                els = els.obj("else")
            } else {
                w.line("} else {")
                val e = els
                w.indent { stmt(e) }
                els = null
            }
        }
        w.line("}")
    }

    // ---- loops

    private fun pushLoop(goLabel: String?): Target {
        val t = Target(goLabel, Target.Kind.LOOP, fn.freshLabel())
        fn.frame.targets.addLast(t)
        return t
    }

    private fun forStmt(s: Node, goLabel: String?) {
        s.obj("init")?.let { stmt(it) }
        val cond = s.obj("cond")?.let { lower(it).code } ?: "true"
        val post = s.obj("post")
        val bodyNode = s.reqObj("body")
        val t = pushLoop(goLabel)
        try {
            if (post != null && continues(bodyNode, goLabel)) {
                val first = fn.fresh("first")
                w.line("var $first = true")
                w.block("${t.kLabel}@ while (true)") {
                    w.line("if ($first) $first = false else {")
                    w.indent { stmt(post) }
                    w.line("}")
                    if (cond != "true") w.line("if (!(${cond})) break")
                    body(bodyNode.list("list"))
                }
            } else {
                w.block("${t.kLabel}@ while ($cond)") {
                    body(bodyNode.list("list"))
                    post?.let { stmt(it) }
                }
            }
        } finally {
            fn.frame.targets.removeLast()
        }
    }

    /** Whether [body] holds a `continue` targeting the loop being lowered (not a nested one). */
    private fun continues(body: Node, goLabel: String?): Boolean {
        var found = false
        fun walk(n: Any?, nested: Boolean) {
            when (n) {
                is kotlinx.serialization.json.JsonObject -> {
                    if (found) return
                    when (n.str("k")) {
                        "FuncLit" -> return
                        "ForStmt", "RangeStmt" -> {
                            for ((k, v) in n) if (k == "body") walk(v, true)
                            return
                        }
                        "BranchStmt" -> if (n.str("tok") == "continue") {
                            val l = n.obj("label")?.str("name")
                            if ((l == null && !nested) || (l != null && l == goLabel)) found = true
                        }
                    }
                    for (v in n.values) walk(v, nested)
                }
                is kotlinx.serialization.json.JsonArray -> for (v in n) walk(v, nested)
                else -> {}
            }
        }
        walk(body, false)
        return found
    }

    private fun rangeStmt(s: Node, goLabel: String?) {
        val rk = s.str("rk")!!
        val x = s.reqObj("x")
        val key = s.obj("key")?.takeUnless { it.bool("blank") || it.str("name") == "_" }
        val value = s.obj("value")?.takeUnless { it.bool("blank") || it.str("name") == "_" }
        val define = s.str("tok") == ":="
        val t = pushLoop(goLabel)
        try {
            when (rk) {
                "slice", "array", "ptrarray" -> {
                    val sv = fn.fresh("s")
                    val base = if (rk == "ptrarray") nn(lower(x)).code else raw(x).code
                    w.line("val $sv = $base")
                    val i = fn.fresh("i")
                    val n = if (rk == "slice") "$sv.len" else "$sv.size"
                    w.block("${t.kLabel}@ for ($i in 0 until $n)") {
                        bindRange(key, Ex.primary(i), define)
                        val v = if (s.bool("valueCopy") && tm.hasGoCopy(s.int("vt") ?: -1)) "$sv[$i].goCopy()" else "$sv[$i]"
                        bindRange(value, Ex.primary(v), define)
                        body(s.reqObj("body").list("list"))
                    }
                }
                "int" -> {
                    val i = fn.fresh("i")
                    val n = raw(x)
                    val rep = tm.repOf(ty(x))
                    val nCode = if (rep == Rep.INT) n.code else "${n.at(PRIMARY)}.toInt()"
                    w.block("${t.kLabel}@ for ($i in 0 until $nCode)") {
                        val kt = s.int("kt")
                        bindRange(key, if (kt != null && tm.isValueClass(kt)) Ex.primary("${tm.kt(kt)}($i)") else Ex.primary(i), define)
                        body(s.reqObj("body").list("list"))
                    }
                }
                "string" -> {
                    val sv = fn.fresh("s")
                    val pos = fn.fresh("p")
                    w.line("val $sv = ${raw(x).code}")
                    w.line("var $pos = 0")
                    w.block("${t.kLabel}@ while ($pos < $sv.length)") {
                        val rw = fn.fresh("rw")
                        val at = fn.fresh("at")
                        w.line("val $at = $pos")
                        w.line("val $rw = goDecodeRune($sv, $pos)")
                        w.line("$pos += ($rw ushr 32).toInt()")
                        bindRange(key, Ex.primary(at), define)
                        bindRange(value, Ex.primary("$rw.toInt()"), define)
                        body(s.reqObj("body").list("list"))
                    }
                }
                "map" -> {
                    val m = fn.fresh("m")
                    val k = fn.fresh("k")
                    w.line("val $m = ${raw(x).code}")
                    w.block("${t.kLabel}@ for ($k in $m.keysSnapshot())") {
                        val lv = fn.fresh("e")
                        w.line("val $lv = $m.probe($k)")
                        w.line("if ($lv === GoMapAbsent) continue")
                        bindRange(key, Ex.primary(k), define)
                        val vt = (types.under(types.core(ty(x))) as MapType).elem
                        val read = "goProbeValue<${tm.kt(vt)}>($lv) { ${tm.zero(vt)} }"
                        val vv = if ((s.bool("valueCopy") || tm.isStructValue(vt)) && tm.hasGoCopy(vt)) "$read.goCopy()" else read
                        bindRange(value, Ex.primary(vv), define)
                        body(s.reqObj("body").list("list"))
                    }
                }
                "func" -> rangeFunc(s, t, key, value, define)
                else -> refuse("range", rk)
            }
        } finally {
            fn.frame.targets.removeLast()
        }
    }

    /** `for k, v := range seq` over a Go iterator: the body becomes the yield function. */
    private fun rangeFunc(s: Node, t: Target, key: Node?, value: Node?, define: Boolean) {
        fn.frame.targets.removeLast()
        val ft = Target(t.goLabel, Target.Kind.RANGE_FUNC, t.kLabel)
        fn.frame.targets.addLast(ft)
        // A `return` in the body: the yield function records it and stops the iteration; the
        // function returns after the iterator call (nested range-over-func loops share the locals).
        val hasReturn = containsReturn(s.reqObj("body"))
        val outerReturn = fn.frame.rangeReturn
        if (hasReturn && outerReturn == null) {
            val done = fn.fresh("rfDone")
            val ret = fn.fresh("rfRet")
            w.line("var $done = false")
            val res = fn.frame.results
            if (res.isNotEmpty()) w.line("var $ret: ${tm.resultKt(res).removeSuffix("?")}? = null")
            fn.frame.rangeReturn = done to ret
        }
        val x = s.reqObj("x")
        val seqT = types.under(types.core(ty(x))) as? SignatureType ?: refuse("range-func-type")
        val yieldT = types.under(seqT.params[0].t) as SignatureType
        val ps = yieldT.params.map { p -> fn.fresh("y") to p.t }
        val outer = fn.w
        val inner = CodeWriter(outer.depth + 1)
        fn.w = inner
        try {
            if (ps.isNotEmpty()) bindRange(key, Ex.primary(ps[0].first), define)
            if (ps.size > 1) bindRange(value, Ex.primary(ps[1].first), define)
            body(s.reqObj("body").list("list"))
            w.line("return true")
        } finally {
            fn.w = outer
        }
        val decl = ps.joinToString(", ") { "${it.first}: ${tm.kt(it.second)}" }
        w.line("${raw(x).at(PRIMARY)}!!(fun($decl): Boolean {")
        w.raw(inner.toString().trimEnd())
        w.line("})")
        if (hasReturn) {
            val (done, ret) = fn.frame.rangeReturn!!
            if (outerReturn != null) {
                w.line("if ($done) return false")
            } else {
                fn.frame.rangeReturn = null
                val res = fn.frame.results
                if (res.isEmpty()) w.line("if ($done) return") else w.line("if ($done) return $ret as ${tm.resultKt(res)}")
            }
        }
    }

    private fun containsReturn(body: Node): Boolean {
        var found = false
        Program.walk(body) { n ->
            if (n.str("k") == "ReturnStmt") found = true
            !found && n.str("k") != "FuncLit"
        }
        return found
    }

    private fun bindRange(target: Node?, v: Ex, define: Boolean) {
        if (target == null) return
        if (define && target.bool("def")) declareLocal(target.int("obj")!!, v.code)
        else store(target, v)
    }

    // ---- switch

    private fun switchStmt(s: Node, goLabel: String?) {
        s.obj("init")?.let { stmt(it) }
        val tag = s.obj("tag")
        val clauses = s.list("clauses")
        val t = Target(goLabel, Target.Kind.SWITCH, fn.freshLabel("sw"))
        t.switchBroken = breaks(clauses, goLabel)
        fn.frame.targets.addLast(t)
        try {
            if (t.switchBroken) {
                w.line("run ${t.kLabel}@{")
                w.push()
            }
            val tagCode = tag?.let { raw(it) }
            val tagVc = tag != null && tm.isValueClass(ty(tag))
            w.line(if (tagCode != null) "when (${tagCode.code}) {" else "when {")
            w.indent {
                var default: List<Node>? = null
                val split = fn.splitSwitch === s
                clauses.forEachIndexed { ci, c ->
                    val bodyStmts = clauseBody(clauses, ci)
                    if (c.bool("default")) {
                        default = bodyStmts
                        return@forEachIndexed
                    }
                    if (split && ci !in fn.splitKeep) return@forEachIndexed
                    val conds = c.list("list").map { v ->
                        if (tag != null) (if (tagVc) raw(v) else flow(v)).code else lower(v).at(Ex.OR + 1)
                    }
                    w.line("${conds.joinToString(if (tag != null) ", " else " || ")} -> {")
                    w.indent { body(bodyStmts) }
                    w.line("}")
                }
                default?.let { d ->
                    w.line("else -> {")
                    w.indent { body(d) }
                    w.line("}")
                }
                // Kotlin requires a `when` over a Boolean subject to be exhaustive (`switch true {…}`).
                if (default == null && tag != null && tm.repOf(ty(tag)) == Rep.BOOL && !tagVc) w.line("else -> {}")
            }
            w.line("}")
            if (t.switchBroken) {
                w.pop()
                w.line("}")
            }
        } finally {
            fn.frame.targets.removeLast()
        }
    }

    /** A clause's body with `fallthrough` resolved by appending the next clause's body. */
    private fun clauseBody(clauses: List<Node>, i: Int): List<Node> {
        val b = clauses[i].list("body")
        val last = b.lastOrNull()
        if (last != null && last.k == "BranchStmt" && last.str("tok") == "fallthrough") {
            return b.dropLast(1) + clauseBody(clauses, i + 1)
        }
        return b
    }

    /** Whether a `break` in [clauses] targets this switch. */
    private fun breaks(clauses: List<Node>, goLabel: String?): Boolean {
        var found = false
        fun walk(n: Any?, nested: Boolean) {
            when (n) {
                is kotlinx.serialization.json.JsonObject -> {
                    if (found) return
                    when (n.str("k")) {
                        "FuncLit" -> return
                        "ForStmt", "RangeStmt", "SwitchStmt", "TypeSwitchStmt", "SelectStmt" -> {
                            for (v in n.values) walk(v, true)
                            return
                        }
                        "BranchStmt" -> if (n.str("tok") == "break") {
                            val l = n.obj("label")?.str("name")
                            if ((l == null && !nested) || (l != null && l == goLabel)) found = true
                        }
                    }
                    for (v in n.values) walk(v, nested)
                }
                is kotlinx.serialization.json.JsonArray -> for (v in n) walk(v, nested)
                else -> {}
            }
        }
        for (c in clauses) walk(c["body"], false)
        return found
    }

    private fun typeSwitchStmt(s: Node, goLabel: String?) {
        s.obj("init")?.let { stmt(it) }
        val clauses = s.list("clauses")
        val t = Target(goLabel, Target.Kind.SWITCH, fn.freshLabel("sw"))
        t.switchBroken = breaks(clauses, goLabel)
        fn.frame.targets.addLast(t)
        try {
            if (t.switchBroken) {
                w.line("run ${t.kLabel}@{")
                w.push()
            }
            val xv = fn.fresh("x")
            w.line("val $xv = ${lower(s.reqObj("x")).code}")
            w.line("when {")
            w.indent {
                var default: Node? = null
                // Go's typed nil: an interface holding a nil `*T` matches `case *T`; Kotlin has one null.
                // When no clause names `nil`, the first single-pointer-type clause whose body tests its
                // variable against nil also takes null (`printer.tryGetEnd`).
                val nilCase = clauses.any { c -> c.list("types").any { te -> te.str("m") == "nil" || (te.k == "Ident" && te.str("name") == "nil") } }
                var nilTaken = nilCase
                for (c in clauses) {
                    if (c.bool("default")) {
                        default = c
                        continue
                    }
                    val tys = c.list("types")
                    val conds = tys.map { te ->
                        if (te.str("m") == "nil" || (te.k == "Ident" && te.str("name") == "nil")) "$xv == null"
                        else "$xv is ${isCheck(ty(te))}"
                    }.toMutableList()
                    val takesNil = !nilTaken && tys.size == 1 && types.under(ty(tys[0])) is PointerType &&
                        c.int("implicit")?.let { comparesToNil(c["body"], it) } == true
                    if (takesNil) {
                        conds += "$xv == null"
                        nilTaken = true
                    }
                    w.line("${conds.joinToString(" || ")} -> {")
                    w.indent {
                        bindTypeCase(c, xv, if (tys.size == 1 && tys[0].str("m") != "nil") ty(tys[0]) else null, takesNil)
                        body(c.list("body"))
                    }
                    w.line("}")
                }
                default?.let { d ->
                    w.line("else -> {")
                    w.indent {
                        bindTypeCase(d, xv, null)
                        body(d.list("body"))
                    }
                    w.line("}")
                }
            }
            w.line("}")
            if (t.switchBroken) {
                w.pop()
                w.line("}")
            }
        } finally {
            fn.frame.targets.removeLast()
        }
    }

    private fun comparesToNil(body: Any?, id: Int): Boolean {
        var found = false
        Program.walk(body) { n ->
            if (n.str("k") == "BinaryExpr" && n.bool("cmp")) {
                val x = n.obj("x")
                val y = n.obj("y")
                if ((x?.int("obj") == id && y?.str("m") == "nil") || (y?.int("obj") == id && x?.str("m") == "nil")) found = true
            }
            !found
        }
        return found
    }

    private fun bindTypeCase(c: Node, xv: String, single: Int?, nullable: Boolean = false) {
        val id = c.int("implicit") ?: return
        if (!usesObj(c["body"], id)) return
        val n = fn.declare(id)
        val t = pc.obj(id).int("t")!!
        if (single != null && tm.boxOf(single) != null) w.line("val $n: ${tm.kt(t)} = ($xv as ${castTarget(single)}).value")
        else if (single != null && nullable) w.line("val $n: ${tm.kt(t)} = $xv as ${castTarget(single)}?")
        else if (single != null) w.line("val $n: ${tm.kt(t)} = $xv as ${castTarget(single)}")
        else w.line("val $n: ${tm.kt(t)} = $xv")
    }

    private fun usesObj(body: Any?, id: Int): Boolean {
        var found = false
        Program.walk(body) { n ->
            if (n.str("k") == "Ident" && n.int("obj") == id) found = true
            !found
        }
        return found
    }

    // ---- branches

    private fun branch(s: Node) {
        val tok = s.str("tok")!!
        val label = s.obj("label")?.str("name")
        val targets = fn.frame.targets
        when (tok) {
            "break" -> {
                val t = (if (label != null) targets.lastOrNull { it.goLabel == label } else targets.lastOrNull())
                    ?: refuse("break-target")
                when (t.kind) {
                    Target.Kind.LOOP -> w.line("break@${t.kLabel}")
                    Target.Kind.SWITCH -> w.line("return@${t.kLabel}")
                    Target.Kind.RANGE_FUNC -> w.line("return false")
                }
            }
            "continue" -> {
                val t = (if (label != null) targets.lastOrNull { it.goLabel == label } else targets.lastOrNull { it.kind != Target.Kind.SWITCH })
                    ?: refuse("continue-target")
                when (t.kind) {
                    Target.Kind.LOOP -> w.line("continue@${t.kLabel}")
                    Target.Kind.RANGE_FUNC -> w.line("return true")
                    Target.Kind.SWITCH -> refuse("continue-switch")
                }
            }
            "goto" -> refuse("goto")
            "fallthrough" -> refuse("fallthrough-position")
            else -> refuse("branch", tok)
        }
    }

    // ---- defer

    private fun deferStmt(s: Node) {
        val frame = fn.frame.deferFrame ?: refuse("defer-outside-frame")
        val call = s.reqObj("call")
        val f = call.reqObj("fun")
        if (f.k == "FuncLit" && call.list("args").isEmpty()) {
            val lit = funcLit(f)
            w.line("$frame.defer(${lit.code})")
            return
        }
        // Arguments are evaluated at the defer statement (Go); the call runs at return.
        val args = call.list("args")
        val temps = args.map { a ->
            val n = fn.fresh("da")
            w.line("val $n = ${flow(a).code}")
            n
        }
        if (call.str("call") == "method") {
            val (recv, name, _) = methodTarget(f)
            val rv = fn.fresh("dr")
            w.line("val $rv = ${recv.code}")
            w.line("$frame.defer { $rv.$name(${temps.joinToString(", ")}) }")
            return
        }
        if (call.str("call") == "func") {
            val o = pc.obj((if (f.k == "SelectorExpr") f.reqObj("sel") else f).int("obj")!!)
            w.line("$frame.defer { ${funcRef(o)}(${(dictArgs(f) + temps).joinToString(", ")}) }")
            return
        }
        if (call.str("call") == "builtin" && call.str("builtin") == "panic") {
            w.line("$frame.defer { goPanic(${temps[0]}) }")
            return
        }
        val fv = fn.fresh("df")
        w.line("val $fv = ${raw(f).code}")
        w.line("$frame.defer { $fv!!(${temps.joinToString(", ")}) }")
    }

    companion object {
        val COMPS = listOf("first", "second", "third", "fourth", "fifth")
        val PLAIN_KINDS = setOf("Int", "Int32", "Int64", "Float64", "String", "UntypedInt", "UntypedFloat", "UntypedString", "UntypedRune")
    }
}
