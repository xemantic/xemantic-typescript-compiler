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

import com.xemantic.typescript.goport.emit.Ex
import com.xemantic.typescript.goport.emit.Ex.Companion.ADD
import com.xemantic.typescript.goport.emit.Ex.Companion.AND
import com.xemantic.typescript.goport.emit.Ex.Companion.AS
import com.xemantic.typescript.goport.emit.Ex.Companion.CMP
import com.xemantic.typescript.goport.emit.Ex.Companion.EQ
import com.xemantic.typescript.goport.emit.Ex.Companion.INFIX
import com.xemantic.typescript.goport.emit.Ex.Companion.IS
import com.xemantic.typescript.goport.emit.Ex.Companion.MUL
import com.xemantic.typescript.goport.emit.Ex.Companion.OR
import com.xemantic.typescript.goport.emit.Ex.Companion.PREFIX
import com.xemantic.typescript.goport.emit.Ex.Companion.PRIMARY
import com.xemantic.typescript.goport.ir.Node
import com.xemantic.typescript.goport.ir.bool
import com.xemantic.typescript.goport.ir.int
import com.xemantic.typescript.goport.ir.ints
import com.xemantic.typescript.goport.ir.k
import com.xemantic.typescript.goport.ir.list
import com.xemantic.typescript.goport.ir.mode
import com.xemantic.typescript.goport.ir.obj
import com.xemantic.typescript.goport.ir.reqObj
import com.xemantic.typescript.goport.ir.str
import com.xemantic.typescript.goport.ir.t
import com.xemantic.typescript.goport.lower.TypeMapper.Rep
import com.xemantic.typescript.goport.naming.Naming
import com.xemantic.typescript.goport.types.ArrayType
import com.xemantic.typescript.goport.types.BasicType
import com.xemantic.typescript.goport.types.InterfaceType
import com.xemantic.typescript.goport.types.MapType
import com.xemantic.typescript.goport.types.NamedType
import com.xemantic.typescript.goport.types.PointerType
import com.xemantic.typescript.goport.types.SignatureType
import com.xemantic.typescript.goport.types.SliceType
import com.xemantic.typescript.goport.types.StructType
import com.xemantic.typescript.goport.types.TypeParamType

/** Expression lowering (docs/goport-ir.md § 6, § 7). */
open class ExprLowering(val fn: FnCtx) {

    val tm get() = fn.tm
    val types get() = fn.types
    val pc get() = fn.pc
    val prog get() = pc.prog

    fun ty(e: Node): Int = e.t ?: refuse("expr-without-type", e.k)

    // ------------------------------------------------------------------ entry points

    /** [e] as a value of its own Kotlin type. */
    fun lower(e: Node): Ex {
        if (e.mode == "nil") {
            val to = e.obj("impl")?.int("to") ?: refuse("nil-without-target")
            return Ex.primary(tm.zero(to))
        }
        if (e.mode == "const" && e.obj("c") != null && e.k != "Ident") return constant(e)
        if (e.mode == "const" && e.k == "Ident") return constIdent(e)
        return when (e.k) {
            "Ident" -> ident(e)
            "ParenExpr" -> lower(e.reqObj("x")).let { Ex.primary("(${it.code})").takeIf { _ -> it.prec < PRIMARY } ?: it }
            "BasicLit" -> constant(e)
            "BinaryExpr" -> binary(e)
            "UnaryExpr" -> unary(e)
            "CallExpr" -> call(e)
            "SelectorExpr" -> selector(e)
            "IndexExpr" -> index(e)
            "SliceExpr" -> sliceExpr(e)
            "StarExpr" -> deref(e)
            "CompositeLit" -> composite(e)
            "FuncLit" -> funcLit(e)
            "TypeAssertExpr" -> typeAssert(e)
            else -> refuse("expr", e.k)
        }
    }

    /**
     * [e] flowing into a location (assignment, argument, return, element): materializes the
     * IR's implicit operations — `copy` (Go value semantics) and `impl` (nil / interface).
     */
    fun flow(e: Node): Ex {
        if (prog.primFuncFieldKey(pc.pkg, e) != null) return primFuncConsumer(e)
        // `&x.f` of an opaque type parameter T flowing into an interface (`json.Unmarshal(data, &e.Value)`):
        // T may be instantiated with a non-struct, so the "pointer is the reference" shortcut does not hold
        // — hand out a real pointer to the location.
        if (e.obj("impl")?.str("k") == "iface" && e.k == "UnaryExpr" && e.str("op") == "&") {
            val pt = types.under(ty(e)) as? PointerType
            if (pt != null && tm.opaqueTP(pt.elem)) return Ex.primary("goOpaqueAddr(${tm.elem(pt.elem)}, ${addressOf(e.reqObj("x"), realPointer = true).code})")
        }
        val v = lower(e)
        e.obj("impl")?.let { im -> if (im.str("k") == "iface") im.int("from")?.let { tm.boxOf(it) }?.let { return Ex.primary("$it(${v.code})") } }
        if (e.bool("copy") && tm.hasGoCopy(ty(e)) && !freshValue(e)) return Ex.primary("${v.at(PRIMARY)}.goCopy()")
        return v
    }

    /**
     * Whether [n] evaluates to a struct object nothing else references — what the IR's `copy` already
     * assumes of a composite literal and of a call's result (docs/goport-ir.md § 7.2) — extended through
     * conversions: `CacheHashKey(b.h.Sum128())` converts a fresh value, so its `copy` flag (a conversion
     * is never FRESH to the extractor) would only duplicate it (docs/goport-perf.md § 6).
     */
    fun freshValue(n: Node): Boolean = when (n.k) {
        "ParenExpr" -> freshValue(n.reqObj("x"))
        "CompositeLit" -> true
        "CallExpr" -> when (n.str("call")) {
            "func", "method", "methodexpr", "dynamic" -> true
            "conv" -> n.list("args").singleOrNull()?.let { freshValue(it) } == true
            else -> false
        }
        else -> false
    }

    /**
     * A slice expression that yields a header nothing else holds (or the shared nil slice, which no owned
     * operation changes): `make`, a composite literal, a sub-slice, `nil` (docs/goport-lowering.md § 3,
     * "Owned slice fields").
     */
    fun freshSliceHeader(n: Node): Boolean = when (n.k) {
        "ParenExpr" -> freshSliceHeader(n.reqObj("x"))
        "CompositeLit" -> true
        "SliceExpr" -> n.str("sk") in setOf("slice", "array", "ptrarray")
        "CallExpr" -> n.str("call") == "builtin" && n.str("builtin") == "make"
        "Ident" -> n.str("m") == "nil"
        else -> false
    }

    /** [code] (the lowered [value]) as a header an owned slice field may keep: copied unless fresh. */
    fun ownedSliceValue(value: Node?, code: Ex): String =
        if (value != null && freshSliceHeader(value)) code.code else "${code.at(PRIMARY)}.ownedCopy()"

    /** [e] as its UNDERLYING representation: a value class is unwrapped (`.value`). */
    fun raw(e: Node): Ex {
        val t = ty(e)
        if (!tm.isValueClass(t)) return lower(e)
        if (e.mode == "const" && e.obj("c") != null) return constRaw(e)
        return when (e.k) {
            "ParenExpr" -> raw(e.reqObj("x"))
            "BinaryExpr" -> if (e.bool("cmp") || e.str("op") in CMP_OPS || e.str("op") in LOGIC_OPS) lower(e) else binaryRaw(e)
            "UnaryExpr" -> if (e.str("op") == "-" || e.str("op") == "^" || e.str("op") == "+") unaryRaw(e) else unwrap(lower(e))
            "CallExpr" -> if (e.str("call") == "conv") convertRaw(e) else unwrap(lower(e))
            else -> unwrap(lower(e))
        }
    }

    fun unwrap(v: Ex): Ex = Ex.primary("${v.at(PRIMARY)}.value")

    /** Wraps a raw underlying value as Kotlin type [t] (a value class), or leaves it. */
    fun wrap(rawEx: Ex, t: Int): Ex = if (tm.isValueClass(t)) Ex.primary("${tm.kt(t)}(${rawEx.code})") else rawEx

    // ------------------------------------------------------------------ constants

    fun constant(e: Node): Ex = wrap(constRaw(e), ty(e))

    fun constRaw(e: Node): Ex {
        val c = e.obj("c") ?: refuse("const-without-value")
        val t = ty(e)
        val b = types.basic(t) ?: refuse("const-non-basic", types[t].key)
        return Literals.raw(c, tm.rep(b))
    }

    /** A named constant: referenced by name when its object's type is the expression's type. */
    private fun constIdent(e: Node): Ex {
        val id = e.int("obj")
        if (id != null) {
            val o = pc.obj(id)
            if (o.str("k") == "const" && o.int("t") == e.t && o.str("pkg") != null && o.bool("local").not() &&
                o.str("pkg") in prog.ported && !types.basic(e.t!!)!!.untyped
            ) {
                val name = prog.constName(o.str("key")!!, o.str("name")!!)
                if (o.str("pkg") == pc.pkg.path && name !in fn.classMembers) return Ex.primary(name)
                return Ex.primary(naming(o.str("pkg")!!) + "." + name)
            }
        }
        return constant(e)
    }

    fun naming(goPath: String) = Naming.kotlinPackage(goPath)

    // ------------------------------------------------------------------ identifiers

    fun ident(e: Node): Ex {
        val name = e.str("name")
        if (e.bool("blank")) refuse("blank-read")
        val id = e.int("obj") ?: refuse("ident-without-object", name ?: "?")
        val o = pc.obj(id)
        return when (o.str("k")) {
            "var", "param", "result", "recv" -> {
                if (id == fn.recvObj) return Ex.primary("this")
                if (id in fn.views) refuse("view-escape", name ?: "?")
                if (o.bool("local")) {
                    val n = fn.nameOf(id) ?: fn.declare(id)
                    if (id in fn.boxed) Ex.primary("$n.value") else Ex.primary(n)
                } else globalVar(o)
            }
            "func" -> funcValue(o, e)
            "const" -> constant(e)
            "nil" -> Ex.primary("null")
            else -> refuse("ident-kind", o.str("k") ?: "?")
        }
    }

    /** A package-level variable reference. */
    fun globalVar(o: Node): Ex {
        val pkg = o.str("pkg") ?: refuse("universe-var")
        val name = if (pkg in prog.ported) prog.varName(o.str("key")!!, o.str("name")!!)
        else Naming.escape(Naming.lowerCamel(o.str("name")!!))
        if (pkg == pc.pkg.path && name !in fn.classMembers) return Ex.primary(name)
        val kp = naming(pkg)
        if (pkg !in prog.ported && !prog.shims.hasTop(kp, name.trim('`'))) refuse("shim-missing", "$pkg.${o.str("name")}")
        return Ex.primary("$kp.$name")
    }

    /** The callable name of a package-level function (bare in its own package unless shadowed). */
    fun funcRef(o: Node): String {
        val pkg = o.str("pkg") ?: refuse("universe-func", o.str("name") ?: "")
        val goName = o.str("name")!!
        val name = if (pkg in prog.ported) prog.funName(o.str("key")!!, goName) else Naming.escape(Naming.lowerCamel(goName))
        if (pkg == pc.pkg.path && name !in fn.classMembers) return name
        val kp = naming(pkg)
        if (pkg !in prog.ported && !prog.shims.hasTop(kp, name.trim('`'))) refuse("shim-missing", "$pkg.$goName")
        return "$kp.$name"
    }

    /** A package-level function used as a VALUE: an anonymous function of the Go signature. */
    fun funcValue(o: Node, e: Node, instFrom: Node = e): Ex {
        val sig = types.unalias(ty(e)) as? SignatureType ?: refuse("func-value-type")
        val ref = funcRef(o)
        val targs = (e.obj("inst") ?: instFrom.obj("inst"))?.ints("targs")?.takeIf { it.isNotEmpty() }?.joinToString(", ", "<", ">") { tm.kt(it) } ?: ""
        val shim = o.str("pkg") !in prog.ported
        val dict = (this as? CallLowering)?.dictArgs(e) ?: emptyList()
        return Ex.primary(wrapperFun(sig) { args0 ->
            val args = dict + args0
            if (shim && sig.variadic) {
                val elemT = (types.under(sig.params.last().t) as SliceType).elem
                val spread = if (tm.kt(elemT) == "Any?") "*${args.last()}.toArray()" else "*${args.last()}.toList()${if (tm.kt(elemT).endsWith("?")) ".map { it!! }" else ""}.toTypedArray()"
                "$ref$targs(${args.dropLast(1).plus(spread).joinToString(", ")})"
            }
            else "$ref$targs(${args.joinToString(", ")})"
        })
    }

    /** `fun(p0: A, p1: B): R = body(p0, p1)` for a Go signature. */
    fun wrapperFun(sig: SignatureType, body: (List<String>) -> String): String {
        val ps = sig.params.indices.map { "p$it" }
        val decl = sig.params.mapIndexed { i, p -> "${ps[i]}: ${tm.kt(p.t)}" }.joinToString(", ")
        return "fun($decl)${tm.returns(sig.results.map { it.t })} = ${body(ps)}"
    }

    // ------------------------------------------------------------------ operators

    fun binary(e: Node): Ex {
        val op = e.str("op")!!
        val x = e.reqObj("x")
        val y = e.reqObj("y")
        return when (op) {
            "&&" -> Ex("${lower(x).at(AND)} && ${lower(y).at(AND + 1)}", AND)
            "||" -> Ex("${lower(x).at(OR)} || ${lower(y).at(OR + 1)}", OR)
            "==", "!=" -> equality(op, x, y)
            "<", "<=", ">", ">=" -> Ex("${raw(x).at(CMP + 1)} $op ${raw(y).at(CMP + 1)}", CMP)
            else -> wrap(binaryRaw(e), ty(e))
        }
    }

    fun binaryRaw(e: Node): Ex = arith(e.str("op")!!, raw(e.reqObj("x")), raw(e.reqObj("y")), ty(e), e.reqObj("y"))

    /**
     * Arithmetic on raw operands of Go type [t]. Narrow integer results are truncated
     * (design § 3); shifts use Go's count semantics unless the count is a small constant.
     */
    fun arith(op: String, l: Ex, r: Ex, t: Int, yNode: Node?): Ex {
        val b = types.basic(t) ?: refuse("arith-non-basic", types[t].key)
        val rep = tm.rep(b)
        val kind = tm.kindOf(b)
        val width = when (rep) {
            Rep.LONG, Rep.ULONG -> 64
            else -> 32
        }
        val res: Ex = when (op) {
            "+" -> Ex("${l.at(ADD)} + ${r.at(ADD + 1)}", ADD)
            "-" -> Ex("${l.at(ADD)} - ${r.at(ADD + 1)}", ADD)
            "*" -> Ex("${l.at(MUL)} * ${r.at(MUL + 1)}", MUL)
            "/" -> Ex("${l.at(MUL)} / ${r.at(MUL + 1)}", MUL)
            "%" -> Ex("${l.at(MUL)} % ${r.at(MUL + 1)}", MUL)
            "&" -> Ex("${l.at(INFIX)} and ${r.at(INFIX + 1)}", INFIX)
            "|" -> Ex("${l.at(INFIX)} or ${r.at(INFIX + 1)}", INFIX)
            "^" -> Ex("${l.at(INFIX)} xor ${r.at(INFIX + 1)}", INFIX)
            "&^" -> Ex("${l.at(INFIX)} and ${r.at(PRIMARY)}.inv()", INFIX)
            "<<", ">>" -> {
                val count = shiftCount(r, yNode)
                val constCount = yNode?.obj("c")?.let { Literals.intValue(it).toInt() }
                val unsignedNarrow = kind == "Uint8" || kind == "Uint16"
                if (constCount != null && constCount in 0 until width) {
                    val kop = if (op == "<<") "shl" else if (unsignedNarrow) "ushr" else "shr"
                    Ex("${l.at(INFIX)} $kop $constCount", INFIX)
                } else {
                    val f = if (op == "<<") "goShl" else if (unsignedNarrow) "goUshr" else "goShr"
                    Ex.primary("$f(${l.code}, ${count.code})")
                }
            }
            else -> refuse("binary-op", op)
        }
        if (rep == Rep.STRING && op != "+") refuse("string-op", op)
        return if (op in TRUNCATING) truncate(res, kind) else res
    }

    private fun shiftCount(r: Ex, yNode: Node?): Ex {
        val yt = yNode?.t ?: return r
        val rep = types.basic(yt)?.let { tm.rep(it) } ?: return r
        return if (rep == Rep.INT) r else Ex.primary("${r.at(PRIMARY)}.toInt()")
    }

    fun truncate(v: Ex, kind: String): Ex = when (kind) {
        "Int8" -> Ex.primary("goInt8(${v.code})")
        "Uint8" -> Ex.primary("goUint8(${v.code})")
        "Int16" -> Ex.primary("goInt16(${v.code})")
        "Uint16" -> Ex.primary("goUint16(${v.code})")
        else -> v
    }

    fun equality(op: String, x: Node, y: Node): Ex {
        val neg = op == "!="
        val xNil = x.mode == "nil"
        val yNil = y.mode == "nil"
        if (xNil || yNil) {
            val other = if (xNil) y else x
            val ot = ty(other)
            val u = types.under(ot)
            return when {
                u is SliceType || u is MapType -> {
                    val v = raw(other)
                    Ex(if (neg) "!${v.at(PRIMARY)}.isNil" else "${v.at(PRIMARY)}.isNil", if (neg) PREFIX else PRIMARY)
                }
                else -> Ex("${lower(other).at(EQ + 1)} ${if (neg) "!=" else "=="} null", EQ)
            }
        }
        // `&s1[i] == &s2[j]`: slot identity (core.Same) — a struct element's `&` is its reference, but an
        // element of an opaque type parameter or a basic type is not; compare the slots themselves.
        fun slot(n: Node): Node? {
            val e = if (n.k == "ParenExpr") n.reqObj("x") else n
            if (e.k != "UnaryExpr" || e.str("op") != "&") return null
            val ix = e.reqObj("x")
            return if (ix.k == "IndexExpr" && ix.str("ik") == "slice") ix else null
        }
        val sx = slot(x)
        val sy = slot(y)
        if (sx != null && sy != null) {
            // One runtime call, no pointer objects (GoSlice.sameSlot: same panics, same answer).
            val same = "${raw(sx.reqObj("x")).at(PRIMARY)}.sameSlot(${intIndex(sx.reqObj("index")).code}, " +
                "${raw(sy.reqObj("x")).code}, ${intIndex(sy.reqObj("index")).code})"
            return if (neg) Ex("!$same", PREFIX) else Ex.primary(same)
        }
        val xt = ty(x)
        val u = types.under(xt)
        val k = if (neg) "!=" else "=="
        if (u is BasicType && tm.rep(u) == Rep.STRING) windowOf(x)?.let { w ->
            // `s[a:b] == t` compares in place (the window is the LEFT operand, so Go's order holds).
            val eq = if (w.to == null) "goStrEqAt(${w.base}, ${w.from}, ${raw(y).code})" else "goStrEqIn(${w.base}, ${w.from}, ${w.to}, ${raw(y).code})"
            return if (neg) Ex("!$eq", PREFIX) else Ex.primary(eq)
        }
        return when {
            u is BasicType -> Ex("${raw(x).at(EQ + 1)} $k ${raw(y).at(EQ + 1)}", EQ)
            u is PointerType && (types.under(u.elem) is StructType || types.under(u.elem) is ArrayType) && types.under(ty(y)) is PointerType ->
                Ex("${lower(x).at(EQ + 1)} ${if (neg) "!==" else "==="} ${lower(y).at(EQ + 1)}", EQ)
            u is StructType || u is ArrayType -> {
                val gen = u is ArrayType || tm.hasGoEquals(xt)
                if (types.under(ty(y)) is InterfaceType || !gen) Ex("${lower(x).at(EQ + 1)} $k ${lower(y).at(EQ + 1)}", EQ)
                else {
                    val eqx = "${lower(x).at(PRIMARY)}.goEquals(${lower(y).code})"
                    if (neg) Ex("!$eqx", PREFIX) else Ex.primary(eqx)
                }
            }
            tm.isValueClass(xt) -> Ex("${raw(x).at(EQ + 1)} $k ${raw(y).at(EQ + 1)}", EQ)
            else -> Ex("${lower(x).at(EQ + 1)} $k ${lower(y).at(EQ + 1)}", EQ)
        }
    }

    fun unary(e: Node): Ex = when (e.str("op")) {
        "!" -> Ex("!${lower(e.reqObj("x")).at(PREFIX)}", PREFIX)
        "&" -> addressOf(e.reqObj("x"))
        "<-" -> refuse("chan")
        else -> wrap(unaryRaw(e), ty(e))
    }

    fun unaryRaw(e: Node): Ex {
        val x = e.reqObj("x")
        val t = ty(e)
        val b = types.basic(t) ?: refuse("unary-non-basic")
        val rep = tm.rep(b)
        val v = raw(x)
        return when (e.str("op")) {
            "+" -> v
            "-" -> when (rep) {
                Rep.UINT -> Ex("0u - ${v.at(ADD + 1)}", ADD)
                Rep.ULONG -> Ex("0uL - ${v.at(ADD + 1)}", ADD)
                else -> truncate(Ex("-${v.at(PREFIX)}", PREFIX), tm.kindOf(b))
            }
            "^" -> truncate(Ex.primary("${v.at(PRIMARY)}.inv()"), tm.kindOf(b))
            else -> refuse("unary-op", e.str("op")!!)
        }
    }

    /** `&x`. A pointer to a struct is the struct reference itself (design § 3). */
    fun addressOf(x: Node, realPointer: Boolean = false): Ex {
        val xt = ty(x)
        val structLike = !realPointer && (tm.isStructValue(xt) || tm.opaqueTP(xt) || types.under(xt) is ArrayType)
        return when (x.k) {
            // `&[]T{}` / `&map[K]V{}`: a pointer to a non-struct value is a box.
            "CompositeLit" -> if (structLike) lower(x) else Ex.primary("GoBox(${lower(x).code})")
            "ParenExpr" -> addressOf(x.reqObj("x"), realPointer)
            "Ident" -> {
                val id = x.int("obj") ?: refuse("addr-ident")
                val o = pc.obj(id)
                if (structLike) return lower(x)
                if (o.bool("local") && id in fn.boxed) return Ex.primary(fn.nameOf(id) ?: fn.declare(id))
                refuse("addr-of", "${o.str("k")} ${o.str("name")}")
            }
            "SelectorExpr" -> {
                if (structLike) return lower(x)
                if (x.str("selk") != "field") refuse("addr-selector")
                val (owner, field) = fieldOwner(x)
                val o = fn.fresh("o")
                val idx = x.ints("path").last()
                Ex.primary("run { val $o = ${owner.code}; GoFieldPtr($o, $idx, { $o.$field }, { $o.$field = it }) }")
            }
            "IndexExpr" -> {
                if (structLike) return lower(x)
                when (x.str("ik")) {
                    "slice" -> Ex.primary("${raw(x.reqObj("x")).at(PRIMARY)}.addr(${intIndex(x.reqObj("index")).code})")
                    else -> refuse("addr-index", x.str("ik")!!)
                }
            }
            "StarExpr" -> lower(x.reqObj("x"))
            else -> refuse("addr-of", x.k)
        }
    }

    fun deref(e: Node): Ex {
        if (e.str("star") != "deref") refuse("type-in-value", "StarExpr")
        val p = e.reqObj("x")
        val pt = types.under(ty(p)) as? PointerType ?: refuse("deref-non-pointer")
        val v = lower(p)
        return when {
            types.under(pt.elem) is StructType || types.under(pt.elem) is ArrayType -> nn(v)
            // `*p` for an opaque T: the "pointer" IS the T value (it may itself be a nil pointer when T
            // is one — `*new(T)`), so no assertion; the cast only narrows the Kotlin type `T?` to `T`.
            tm.opaqueTP(pt.elem) -> Ex("${v.at(AS)} as ${tm.kt(pt.elem)}", AS)
            else -> Ex.primary("${nn(v).code}.value")
        }
    }

    /** [v] (a nullable reference) asserted non-null — Go's nil dereference is Kotlin's NPE. */
    fun nn(v: Ex): Ex = if (v.code == "this" && !fn.recvNullable) v else Ex.primary("${v.at(PRIMARY)}!!")

    // ------------------------------------------------------------------ selectors

    fun selector(e: Node): Ex {
        if (e.bool("qual")) return qualified(e)
        return when (e.str("selk")) {
            "field" -> fieldSelect(e)
            "method" -> methodValue(e)
            "methodexpr" -> methodExprValue(e)
            else -> refuse("selector", e.str("selk") ?: "?")
        }
    }

    private fun qualified(e: Node): Ex {
        val sel = e.reqObj("sel")
        val id = sel.int("obj") ?: refuse("qual-without-object")
        val o = pc.obj(id)
        return when (o.str("k")) {
            "var" -> globalVar(o)
            "func" -> funcValue(o, e)
            "const" -> constant(e)
            else -> refuse("qual-kind", o.str("k") ?: "?")
        }
    }

    /** Kotlin name of field [index] of the struct underlying [structOwner] (a struct or named struct type id). */
    fun fieldNameOf(structOwner: Int, index: Int): String {
        val st = types.under(structOwner) as? StructType ?: refuse("field-of-non-struct")
        val f = st.fields[index]
        val named = types.unalias(structOwner) as? NamedType
        val origin = named?.let { n -> if (n.origin != null) types.unalias(n.origin) as NamedType else n }
            ?: return prog.fieldName(null, f.name, index)
        return prog.fieldName(origin.key + "." + f.name, f.name, index)
    }

    /**
     * Walks the embedding [path] from [base] (of Go type [baseType]); answers the code and the Go
     * type reached. Pointers along the way are dereferenced (`!!`).
     */
    fun walkPath(base: Ex, baseType: Int, path: List<Int>): Pair<Ex, Int> {
        var code = base
        var cur = baseType
        for (i in path) {
            var target = cur
            val p = types.under(cur)
            if (p is PointerType) {
                code = nn(code)
                target = p.elem
            }
            if (tm.isValueClass(target)) refuse("field-of-value-class")
            val st = types.under(target) as? StructType ?: refuse("path-through-non-struct", types[target].key)
            // A transparent wrapper's only field is the wrapper itself (Program.transparentWrappers): no step.
            val ownerKey = (types.unalias(target) as? NamedType)?.let { n -> n.origin?.let { (types.unalias(it) as NamedType).key } ?: n.key }
            if (ownerKey !in prog.transparentWrappers) {
                val name = fieldNameOf(target, i)
                code = Ex.primary("${code.at(PRIMARY)}.$name")
            }
            cur = st.fields[i].t
        }
        return code to cur
    }

    fun fieldSelect(e: Node): Ex {
        val x = e.reqObj("x")
        if (prog.windowFieldKey(pc.pkg, e) != null) {
            // A window field read as a whole string: materialized (the base itself when the window covers it).
            viewOf(e)?.let { v -> return Ex.primary("goStrWin(${v.base}, ${v.off}, ${v.len})") }
            val (owner, name) = fieldOwner(e)
            val t = fn.fresh("wf")
            return Ex.primary("run { val $t = ${owner.code}; goStrWin($t.$name, $t.${name}_o, $t.${name}_n) }")
        }
        return walkPath(lower(x), e.int("recv") ?: ty(x), e.ints("path")).first
    }

    /** For `&x.f`: the (non-null) owner object code and the Kotlin field name. */
    fun fieldOwner(e: Node): Pair<Ex, String> {
        val x = e.reqObj("x")
        val path = e.ints("path")
        val (owner, ownerT) = walkPath(lower(x), e.int("recv") ?: ty(x), path.dropLast(1))
        var o = owner
        var t = ownerT
        val p = types.under(t)
        if (p is PointerType) {
            o = nn(o)
            t = p.elem
        }
        (types.unalias(t) as? NamedType)?.takeIf { it.key in prog.transparentWrappers }?.let {
            error("goport: a store or address of a transparent wrapper's field (${it.key}) — refuse the wrapper in Program.computeTransparentWrappers")
        }
        return o to fieldNameOf(t, path.last())
    }

    /** The receiver of a method selection with the method's Kotlin name, `goCopy` applied when Go copies. */
    fun methodTarget(e: Node): Triple<Ex, String, Boolean> {
        val x = e.reqObj("x")
        val path = e.ints("path")
        val (recv, recvT) = walkPath(lower(x), e.int("recv") ?: ty(x), path.dropLast(1))
        val sel = e.reqObj("sel")
        val mo = pc.obj(sel.int("obj") ?: refuse("method-without-object"))
        val key = mo.str("origin")?.let { pc.obj(it.toInt()).str("key") } ?: mo.str("key") ?: ""
        val goName = mo.str("name")!!
        val mpkg = mo.str("pkg")
        val name = if (mpkg == null) Naming.escape(Naming.lowerCamel(goName))
        else if (mpkg in prog.ported) prog.methodName(key, goName)
        else Naming.escape(Naming.lowerCamel(goName))
        if (mpkg != null && mpkg !in prog.ported && !prog.shims.hasMember(naming(mpkg), name.trim('`'))) {
            refuse("shim-missing", "$mpkg.${key.substringAfterLast('/').substringAfter('.')}")
        }
        val nilSafe = key in prog.extensionMethods
        if (nilSafe && mpkg != null && mpkg != pc.pkg.path) fn.fc.importFun(naming(mpkg), name)
        if (mpkg != null && mpkg !in prog.ported && prog.shims.isExtension(naming(mpkg), name.trim('`'))) fn.fc.importFun(naming(mpkg), name.trim('`'))
        var r = recv
        // `(*T)(nil).M()` with M never reading its receiver: any instance of T will do.
        val xi = if (x.k == "ParenExpr") x.reqObj("x") else x
        if (path.size == 1 && !nilSafe && key in prog.recvUnusedMethods && xi.k == "CallExpr" && xi.str("call") == "conv" &&
            xi.list("args").singleOrNull()?.str("m") == "nil"
        ) {
            val pt = types.under(ty(x)) as? PointerType
            if (pt != null && tm.isStructValue(pt.elem)) return Triple(Ex.primary(tm.zero(pt.elem)), name, false)
        }
        val u = types.under(recvT)
        val isPtr = u is PointerType
        if (e.bool("ifaceMethod") || types.under(recvT) is InterfaceType) {
            r = nn(r)
        } else if (isPtr && !nilSafe) {
            r = nn(r)
        } else if (isPtr && nilSafe && !e.bool("ptrRecv")) {
            // a VALUE-receiver extension (non-null receiver) through a pointer: Go dereferences
            r = nn(r)
        } else if (isPtr && nilSafe) {
            // extension on T?: no assertion
        } else if (types.isPointer(recvT).not() && e.bool("recvCopy") && key in prog.receiverMutators) {
            r = Ex.primary("${r.at(PRIMARY)}.goCopy()")
        }
        return Triple(r, name, nilSafe)
    }

    /** An extension method used from another package must be imported (`fc.importFun`). */
    fun importExtension(mo: Node, name: String) {
        val mpkg = mo.str("pkg") ?: return
        if (mo.str("key") in prog.extensionMethods && mpkg != pc.pkg.path) fn.fc.importFun(naming(mpkg), name)
    }

    /** A method value; with [sam], the bound function is SAM-converted to that `fun interface` (Program.primFuncFields). */
    fun methodValue(e: Node, sam: String? = null): Ex {
        val (r, name, _) = methodTarget(e)
        val sig = types.unalias(e.int("selt") ?: ty(e)) as? SignatureType ?: refuse("method-value-sig")
        val rv = fn.fresh("r")
        val f = wrapperFun(sig) { a -> "$rv.$name(${a.joinToString(", ")})" }
        return Ex.primary("run { val $rv = ${r.code}; ${if (sam == null) f else "$sam($f)"} }")
    }

    /**
     * A value for a [Program.primFuncFields] field: nil, or a method value / function literal SAM-converted to the
     * field's `fun interface` right here, so the lambda's own method returns the primitive.
     */
    fun primFuncProducer(key: String, r: Node): Ex {
        val iface = fn.fc.typeRef(Naming.kotlinPackage(key.substringBeforeLast('.').substringBeforeLast('.')), prog.primFuncIfaceName(key))
        return when {
            r.mode == "nil" -> Ex.primary("null")
            r.k == "FuncLit" -> Ex.primary("$iface(${lower(r).code})")
            r.k == "SelectorExpr" && r.str("selk") == "method" -> methodValue(r, iface)
            else -> refuse("prim-func-producer", r.k)
        }
    }

    /** A [Program.primFuncFields] field read handed to a function: adapted back to a Kotlin function type (nil stays nil). */
    private fun primFuncConsumer(e: Node): Ex {
        val sig = types.unalias(ty(e)) as? SignatureType ?: refuse("prim-func-consumer-sig")
        val g = fn.fresh("g")
        return Ex.primary("${lower(e).at(PRIMARY)}?.let { $g -> ${wrapperFun(sig) { a -> "$g(${a.joinToString(", ")})" }} }")
    }

    fun methodExprValue(e: Node): Ex {
        val sig = types.unalias(ty(e)) as? SignatureType ?: refuse("methodexpr-sig")
        val sel = e.reqObj("sel")
        val mo = pc.obj(sel.int("obj") ?: refuse("method-without-object"))
        val name = prog.methodName(mo.str("key") ?: "", mo.str("name")!!)
        importExtension(mo, name)
        return Ex.primary(wrapperFun(sig) { a ->
            val recvT = sig.params[0].t
            val r = if (tm.nullable(recvT) && mo.str("key") !in prog.extensionMethods) "${a[0]}!!" else a[0]
            "$r.$name(${a.drop(1).joinToString(", ")})"
        })
    }

    // ------------------------------------------------------------------ index / slice

    fun intIndex(i: Node): Ex {
        val r = raw(i)
        val rep = tm.repOf(ty(i)) ?: refuse("index-type")
        return if (rep == Rep.INT) r else Ex.primary("${r.at(PRIMARY)}.toInt()")
    }

    fun index(e: Node): Ex {
        val x = e.reqObj("x")
        return when (e.str("ik")) {
            "slice", "array" -> Ex.primary("${raw(x).at(PRIMARY)}[${intIndex(e.reqObj("index")).code}]")
            "ptrarray" -> Ex.primary("${nn(lower(x)).code}[${intIndex(e.reqObj("index")).code}]")
            "string" -> viewOf(x)?.let { v -> Ex.primary("goViewByte(${v.base}, ${v.off}, ${v.len}, ${intIndex(e.reqObj("index")).code})") }
                ?: Ex.primary("${raw(x).at(PRIMARY)}[${intIndex(e.reqObj("index")).code}].code")
            "map" -> Ex.primary("${raw(x).at(PRIMARY)}[${flow(e.reqObj("index")).code}]")
            // `f[T]` as a VALUE (`unmarshallerFor[P]` in a map literal): the instantiated function, like an
            // implicitly instantiated generic function value (funcValue), with T's dictionaries bound.
            "instantiate" -> {
                val ident = when (x.k) { "Ident" -> x; "SelectorExpr" -> x.reqObj("sel"); else -> refuse("generic-func-value") }
                val o = pc.obj(ident.int("obj") ?: refuse("generic-func-value"))
                if (o.str("k") != "func") refuse("generic-func-value")
                funcValue(o, e, ident)
            }
            else -> refuse("index-kind", e.str("ik") ?: "?")
        }
    }

    // ------------------------------------------------------------------ string windows

    /** A string window `base[from:to]` (`to == null`: to the end of `base`) — Go's O(1) slice, no copy. */
    class Window(val base: String, val from: String, val to: String?)

    /**
     * The view [x] names, if it is one (docs/goport-lowering.md § 3, substring elimination): a view
     * local, or a window FIELD selection `o.f` ([Program.windowFields]) over its slots `f`, `f_o`,
     * `f_n` when the owner `o` is safe to evaluate more than once.
     */
    fun viewOf(x: Node): View? = when (x.k) {
        "Ident" -> x.int("obj")?.let { fn.views[it] }
        "ParenExpr" -> viewOf(x.reqObj("x"))
        "SelectorExpr" -> if (prog.windowFieldKey(pc.pkg, x) == null) null else {
            val (owner, name) = fieldOwner(x)
            val o = owner.at(PRIMARY)
            if (SIMPLE_OWNER.matches(o)) View("$o.$name", "$o.${name}_o", "$o.${name}_n") else null
        }
        else -> null
    }

    /** An owner expression safe to evaluate repeatedly: a name or a field chain (no call, no side effect). */
    private val SIMPLE_OWNER = Regex("""[A-Za-z_][A-Za-z0-9_]*(!!)?(\.[A-Za-z_][A-Za-z0-9_]*(!!)?)*""")

    /**
     * [a] as a window when it is a string slice `s[lo:hi]` with at least one bound — of a plain
     * string, or of a view local (whose sub-slice bounds are checked against the VIEW, as Go does).
     * The parts are rendered in Go's evaluation order: base, then low, then high.
     */
    fun windowOf(a: Node): Window? {
        if (a.k != "SliceExpr" || a.str("sk") != "string" || a.bool("slice3")) return null
        val lo = a.obj("low")
        val hi = a.obj("high")
        if (lo == null && hi == null) return null
        val x = a.reqObj("x")
        val v = viewOf(x)
        if (v != null) {
            val from = if (lo == null) v.off else "${v.off} + goViewBound(${v.len}, ${intIndex(lo).code})"
            val to = if (hi == null) "${v.off} + ${v.len}" else "${v.off} + goViewBound(${v.len}, ${intIndex(hi).code})"
            return Window(v.base, from, to)
        }
        return Window(raw(x).code, lo?.let { intIndex(it).code } ?: "0", hi?.let { intIndex(it).code })
    }

    fun sliceExpr(e: Node): Ex {
        val x = e.reqObj("x")
        val lo = e.obj("low")?.let { intIndex(it).code }
        val hi = e.obj("high")?.let { intIndex(it).code }
        val r = when (e.str("sk")) {
            "string" -> {
                // A sub-slice of a view as a string value: one checked substring of the base.
                viewOf(x)?.let { v ->
                    return wrap(Ex.primary("goViewSubstring(${v.base}, ${v.off}, ${v.len}, ${lo ?: "0"}, ${hi ?: v.len})"), ty(e))
                }
                val s = raw(x)
                when {
                    hi == null && lo == null -> s
                    hi == null -> Ex.primary("${s.at(PRIMARY)}.substring($lo)")
                    else -> Ex.primary("${s.at(PRIMARY)}.substring(${lo ?: "0"}, $hi)")
                }
            }
            "slice", "array", "ptrarray" -> {
                val base = if (e.str("sk") == "ptrarray") nn(lower(x)) else raw(x)
                if (e.bool("slice3")) {
                    Ex.primary("${base.at(PRIMARY)}.slice3(${lo ?: "0"}, $hi, ${intIndex(e.reqObj("max")).code})")
                } else {
                    val args = listOfNotNull(lo ?: if (hi != null) "0" else null, hi).joinToString(", ")
                    Ex.primary("${base.at(PRIMARY)}.slice($args)")
                }
            }
            else -> refuse("slice-kind", e.str("sk") ?: "?")
        }
        return wrap(r, ty(e))
    }

    // ------------------------------------------------------------------ type assertions

    fun typeAssert(e: Node): Ex {
        val x = lower(e.reqObj("x"))
        val t = ty(e)
        if (tm.boxOf(t) != null) return Ex.primary("(${x.at(AS)} as ${castTarget(t)}).value")
        return Ex("${x.at(AS)} as ${castTarget(t)}", AS)
    }

    /** The Kotlin type to cast to for Go type [t] (non-null for a pointer: a nil pointer cannot be in an interface). */
    fun castTarget(t: Int): String {
        tm.boxOf(t)?.let { return it }
        val k = tm.kt(t)
        return if (types.under(t) is PointerType && k.endsWith("?")) k.dropLast(1) else k
    }

    /** The `is` check for Go type [t] (erased generics use star projections). */
    fun isCheck(t: Int): String {
        // A slice or map type may be spelled through a typealias (`jsontext.Value` = `GoSlice<Int>`): check the
        // erased container (TSGO.4-a, `lsproto.RequestInfo.UnmarshalResult`'s `result.(json.Value)`).
        if (tm.boxOf(t) == null) when (types.under(t)) {
            is SliceType -> return "com.xemantic.typescript.tsgo.runtime.GoSlice<*>"
            is MapType -> return "com.xemantic.typescript.tsgo.runtime.GoMap<*, *>"
            else -> {}
        }
        val k = castTarget(t)
        val gen = k.indexOf('<')
        return if (gen > 0) k.substring(0, gen) + "<" + k.substring(gen + 1, k.lastIndexOf('>')).split(',').joinToString(", ") { "*" } + ">" else k.removeSuffix("?")
    }

    // ------------------------------------------------------------------ composite literals and closures

    /** Elements of a literal larger than this are filled by hoisted helper functions (a JVM method holds 64 KB). */
    private val chunk = 150

    private fun hoistable(elts: List<Node>): Boolean {
        var ok = true
        Program.walk(elts) { n ->
            if (n.str("k") == "Ident" && n.int("obj")?.let { pc.obj(it).bool("local") } == true) ok = false
            if (n.str("k") == "FuncLit") ok = false
            ok
        }
        return ok
    }

    /**
     * A composite literal's element as the right side of a fill statement. An anonymous function with an
     * expression body is parenthesized: K2's raw-FIR builder treats `it[a] = fun(…) = x; it[b] = fun(…) = y; …`
     * as nested assignments and is EXPONENTIAL in their number (measured: 22 entries 32 s, 26 entries > 200 s;
     * parenthesized, 130 entries 5.6 s) — `api.unmarshalers` (TSGO.3-b) held a whole-module compile for 43 min.
     */
    private fun setValue(v: String): String = if (v.startsWith("fun(")) "($v)" else v

    /** `make.also { …sets… }`, the sets split into hoisted fill helpers when there are many. */
    private fun filled(make: String, receiverType: String, sets: List<String>, elts: List<Node>): String {
        if (sets.isEmpty()) return make
        if (sets.size <= chunk || !hoistable(elts)) return "$make.also { ${sets.joinToString("; ")} }"
        val base = "goLit_" + Naming.lowerCamel(fn.qname.substringAfterLast('/').replace('.', '_').replace('#', '_')) + "_${fn.helpers.size}"
        val calls = sets.chunked(chunk).mapIndexed { i, part ->
            val name = "${base}_$i"
            fn.helpers += "private fun $name(it: $receiverType) {\n" + part.joinToString("") { "    $it\n" } + "}\n"
            "$name(it)"
        }
        return "$make.also { ${calls.joinToString("; ")} }"
    }

    fun composite(e: Node): Ex {
        var t = ty(e)
        val pt = types.under(t)
        if (pt is PointerType) t = pt.elem
        val u = types.under(t)
        val elts = e.list("elts")
        return when (u) {
            is StructType -> {
                // `struct{}` is Unit; a NAMED empty struct (`type star struct{}`) is its class (it implements interfaces).
                if (u.fields.isEmpty() && types.unalias(t) is StructType) return Ex.primary("Unit")
                if (tm.isValueClass(t)) refuse("struct-value-class")
                (types.unalias(t) as? NamedType)?.takeIf { it.key in prog.transparentWrappers }?.let { _ ->
                    // Program.transparentWrappers: `W{E: v}` is `v` (a value copy), `W{}` the wrapped struct's zero.
                    val el = elts.singleOrNull() ?: return Ex.primary("${tm.kt(t).removeSuffix("?")}()")
                    return flow(if (el.k == "KeyValueExpr") el.reqObj("value") else el)
                }
                if (types.unalias(t) is StructType) {
                    val args = elts.map { el ->
                        if (el.k == "KeyValueExpr") {
                            val key = el.reqObj("key")
                            val idx = u.fields.indexOfFirst { it.name == key.str("name") }
                            "${fieldNameOf(t, idx)} = ${flow(el.reqObj("value")).code}"
                        } else "${fieldNameOf(t, el.int("fieldIndex") ?: refuse("positional-field-without-index"))} = ${flow(el).code}"
                    }
                    return Ex.primary("${tm.kt(t)}(${args.joinToString(", ")})")
                }
                // Program.ownedSliceFields: an owned field keeps a header of its own.
                val ownerKey = (types.unalias(t) as? NamedType)?.key
                fun fieldValue(idx: Int, v: Node): String {
                    val code = flow(v)
                    return if (ownerKey != null && "$ownerKey.${u.fields[idx].name}" in prog.ownedSliceFields) ownedSliceValue(v, code) else code.code
                }
                val args = elts.map { el ->
                    if (el.k == "KeyValueExpr") {
                        val key = el.reqObj("key")
                        val fo = pc.obj(key.int("obj") ?: refuse("field-key-without-object"))
                        val idx = u.fields.indexOfFirst { it.name == fo.str("name") }
                        "${fieldNameOf(t, idx)} = ${fieldValue(idx, el.reqObj("value"))}"
                    } else {
                        val idx = el.int("fieldIndex") ?: refuse("positional-field-without-index")
                        "${fieldNameOf(t, idx)} = ${fieldValue(idx, el)}"
                    }
                }
                val dict = (types.unalias(t) as? NamedType)?.let { tm.dictArgs(it) } ?: emptyList()
                if (prog.bigStruct(u)) {
                    // Fields in the class body (Program.bigStruct): construct, then assign.
                    val o = fn.fresh("o")
                    val sets = args.map { a -> "$o.${a.substringBefore(" = ")} = ${a.substringAfter(" = ")}" }
                    return Ex.primary("${tm.kt(t).removeSuffix("?")}(${dict.joinToString(", ")})" + if (sets.isEmpty()) "" else ".also { $o -> ${sets.joinToString("; ")} }")
                }
                Ex.primary("${tm.kt(t).removeSuffix("?")}(${(dict + args).joinToString(", ")})")
            }
            is SliceType, is ArrayType -> {
                val elemT = if (u is SliceType) u.elem else (u as ArrayType).elem
                val elemK = tm.elem(elemT)
                val keyed = elts.any { it.k == "KeyValueExpr" }
                val r = if (u is SliceType && !keyed && elts.size <= chunk) {
                    if (elts.isEmpty()) "GoSlice.make($elemK, 0)" else "GoSlice.of($elemK, ${elts.joinToString(", ") { flow(it).code }})"
                } else {
                    var i = 0L
                    var max = 0L
                    val sets = elts.map { el ->
                        val v: String
                        if (el.k == "KeyValueExpr") {
                            i = Literals.intValue(el.reqObj("key").obj("c") ?: refuse("index-key-not-const")).toLong()
                            v = flow(el.reqObj("value")).code
                        } else v = flow(el).code
                        val s = "it[$i] = ${setValue(v)}"
                        i++
                        if (i > max) max = i
                        s
                    }
                    val n = if (u is ArrayType) u.len else max
                    val make = if (u is ArrayType) "GoArray($n, $elemK)" else "GoSlice.make($elemK, $n)"
                    filled(make, (if (u is ArrayType) "GoArray" else "GoSlice") + "<${tm.kt(elemT)}>", sets, elts)
                }
                wrap(Ex.primary(r), t)
            }
            is MapType -> {
                val sets = elts.map { el ->
                    "it[${flow(el.reqObj("key")).code}] = ${setValue(flow(el.reqObj("value")).code)}"
                }
                val make = "GoMap.make<${tm.kt(u.keyType)}, ${tm.kt(u.elem)}>(${tm.elem(u.elem)})"
                wrap(Ex.primary(filled(make, "GoMap<${tm.kt(u.keyType)}, ${tm.kt(u.elem)}>", sets, elts)), t)
            }
            else -> refuse("composite", types[t].key)
        }
    }

    /** Hook for func literals: implemented by the statement lowering (it owns bodies). */
    open fun funcLit(e: Node): Ex = refuse("funclit")

    // ------------------------------------------------------------------ calls (Calls.kt)

    open fun call(e: Node): Ex = refuse("call")

    open fun convertRaw(e: Node): Ex = refuse("conv")

    companion object {
        val CMP_OPS = setOf("<", "<=", ">", ">=", "==", "!=")
        val LOGIC_OPS = setOf("&&", "||")
        val TRUNCATING = setOf("+", "-", "*", "/", "<<")
    }
}
