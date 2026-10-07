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
import com.xemantic.typescript.goport.emit.Ex.Companion.PRIMARY
import com.xemantic.typescript.goport.ir.Node
import com.xemantic.typescript.goport.ir.bool
import com.xemantic.typescript.goport.ir.int
import com.xemantic.typescript.goport.ir.ints
import com.xemantic.typescript.goport.ir.k
import com.xemantic.typescript.goport.ir.list
import com.xemantic.typescript.goport.ir.obj
import com.xemantic.typescript.goport.ir.reqObj
import com.xemantic.typescript.goport.ir.str
import com.xemantic.typescript.goport.ir.t
import com.xemantic.typescript.goport.lower.TypeMapper.Rep
import com.xemantic.typescript.goport.types.ArrayType
import com.xemantic.typescript.goport.types.BasicType
import com.xemantic.typescript.goport.types.InterfaceType
import com.xemantic.typescript.goport.types.MapType
import com.xemantic.typescript.goport.types.PointerType
import com.xemantic.typescript.goport.types.SignatureType
import com.xemantic.typescript.goport.types.SliceType
import com.xemantic.typescript.goport.types.StructType
import com.xemantic.typescript.goport.types.TypeParamType

/** Calls, conversions and builtins (docs/goport-ir.md § 6.4). */
open class CallLowering(fn: FnCtx) : ExprLowering(fn) {

    override fun call(e: Node): Ex {
        val r = when (e.str("call")) {
            "conv" -> return convert(e)
            "builtin" -> return builtin(e)
            "func" -> funcCall(e)
            "method" -> methodCall(e)
            "methodexpr" -> methodExprCall(e)
            "dynamic" -> dynamicCall(e)
            else -> refuse("call-kind", e.str("call") ?: "?")
        }
        return r
    }

    /** The argument list for a call with signature [sig] (`variadicFrom` packing, `spread`, tuple args). */
    fun args(e: Node, sig: SignatureType, shimVararg: Boolean, shim: Boolean = shimVararg): List<String> {
        val raw0 = args0(e, sig, shimVararg)
        if (!shim) return raw0
        // Shims take non-null function and struct-pointer parameters (docs/goport-runtime.md § 9):
        // Go's nil there panics in Go too.
        val argNodes = e.list("args")
        return raw0.mapIndexed { i, code ->
            val pt = sig.params.getOrNull(minOf(i, sig.params.size - 1))?.t ?: return@mapIndexed code
            val node = argNodes.getOrNull(i)
            val under = types.under(pt)
            val needs = (under is SignatureType || (under is PointerType && types.under(under.elem) is StructType)) &&
                node != null && node.k != "FuncLit" && !code.startsWith("*") && node.str("m") != "nil"
            if (needs) "${Ex(code, 0).at(PRIMARY)}!!" else code
        }
    }

    private fun args0(e: Node, sig: SignatureType, shimVararg: Boolean): List<String> {
        val args = e.list("args")
        if (e.bool("tupleArg")) refuse("tuple-arg")
        val from = e.int("variadicFrom")
        if (from == null) {
            if (e.bool("spread") && shimVararg) {
                val last = args.last()
                val elemT = (types.under(ty(last)) as SliceType).elem
                val spread = if (tm.kt(elemT) == "Any?") "*${raw(last).at(PRIMARY)}.toArray()" else "*${raw(last).at(PRIMARY)}.toList()${if (tm.kt(elemT).endsWith("?")) ".map { it!! }" else ""}.toTypedArray()"
                return args.dropLast(1).map { flow(it).code } + spread
            }
            return args.map { flow(it).code }
        }
        val fixed = args.take(from).map { flow(it).code }
        val rest = args.drop(from)
        if (shimVararg) return fixed + rest.map { flow(it).code }
        val sliceT = sig.params.last().t
        val elemT = (types.under(sliceT) as? SliceType)?.elem ?: refuse("variadic-not-slice")
        val packed = if (rest.isEmpty()) "${tm.elem(elemT)}.nilSlice" else "GoSlice.of(${tm.elem(elemT)}, ${rest.joinToString(", ") { flow(it).code }})"
        return fixed + packed
    }

    private fun sigOf(e: Node): SignatureType =
        types.unalias(e.int("sig") ?: ty(e.reqObj("fun"))) as? SignatureType ?: refuse("call-without-signature")

    /** Explicit type arguments of a generic callee (the IR records inferred ones too). */
    private fun typeArgs(fnExpr: Node): String {
        val ident = when (fnExpr.k) {
            "Ident" -> fnExpr
            "SelectorExpr" -> fnExpr.reqObj("sel")
            "IndexExpr", "IndexListExpr" -> return typeArgs(fnExpr.reqObj("x"))
            "ParenExpr" -> return typeArgs(fnExpr.reqObj("x"))
            else -> return ""
        }
        val targs = ident.obj("inst")?.ints("targs") ?: (fnExpr.obj("inst")?.ints("targs")) ?: return ""
        if (targs.isEmpty()) return ""
        val generic = ident.int("obj")?.let { pc.obj(it).int("t") }?.let { types.unalias(it) as? SignatureType }
        val kept = if (generic != null) tm.keptTypeArgs(generic.tparams, targs) else targs
        if (kept.isEmpty()) return ""
        return kept.joinToString(", ", "<", ">") { tm.kt(it) }
    }

    /** The element kinds a PORTED generic function takes for its type parameters (see funcDecl). */
    fun dictArgs(fnExpr: Node): List<String> {
        val ident = when (fnExpr.k) {
            "Ident" -> fnExpr
            "SelectorExpr" -> fnExpr.reqObj("sel")
            "IndexExpr", "IndexListExpr", "ParenExpr" -> return dictArgs(fnExpr.reqObj("x"))
            else -> return emptyList()
        }
        val o = ident.int("obj")?.let { pc.obj(it) } ?: return emptyList()
        if (o.str("k") != "func" || o.str("pkg") !in prog.ported) return emptyList()
        val generic = o.int("t")?.let { types.unalias(it) as? SignatureType } ?: return emptyList()
        if (generic.tparams.isEmpty()) return emptyList()
        val targs = ident.obj("inst")?.ints("targs") ?: fnExpr.obj("inst")?.ints("targs") ?: refuse("generic-call-without-targs")
        return generic.tparams.indices.filter { !tm.isSubstituted(generic.tparams[it]) }.map { tm.elem(targs[it]) }
    }

    private fun calleeObj(fnExpr: Node): Node {
        val ident = when (fnExpr.k) {
            "Ident" -> fnExpr
            "SelectorExpr" -> fnExpr.reqObj("sel")
            "IndexExpr", "IndexListExpr", "ParenExpr" -> return calleeObj(fnExpr.reqObj("x"))
            else -> refuse("callee", fnExpr.k)
        }
        return pc.obj(ident.int("obj") ?: refuse("callee-without-object"))
    }

    fun funcCall(e: Node): Ex {
        val fnExpr = e.reqObj("fun")
        val o = calleeObj(fnExpr)
        val sig = sigOf(e)
        val shim = o.str("pkg") !in prog.ported
        special(o, e)?.let { return it }
        val ref = funcRef(o)
        return Ex.primary("$ref${typeArgs(fnExpr)}(${(dictArgs(fnExpr) + args(e, sig, shim && sig.variadic, shim)).joinToString(", ")})")
    }

    /** Lowering rules for specific shim calls (docs/goport-runtime.md § 9). */
    private fun special(o: Node, e: Node): Ex? {
        val key = o.str("key") ?: return null
        val args = e.list("args")
        fun suffixArg(a: Node): Pair<Node, Node>? =
            if (a.k == "SliceExpr" && a.str("sk") == "string" && a.obj("high") == null && a.obj("low") != null) a.reqObj("x") to a.reqObj("low") else null
        when (key) {
            "unicode/utf8.DecodeRuneInString" -> suffixArg(args[0])?.let { (s, lo) ->
                return Ex.primary("com.xemantic.typescript.tsgo.go.unicode.utf8.decodeRuneInStringAt(${raw(s).code}, ${intIndex(lo).code})")
            }
            "unicode/utf8.DecodeLastRuneInString" -> {
                val a = args[0]
                if (a.k == "SliceExpr" && a.str("sk") == "string" && a.obj("low") == null && a.obj("high") != null) {
                    return Ex.primary("com.xemantic.typescript.tsgo.go.unicode.utf8.decodeLastRuneInStringBefore(${raw(a.reqObj("x")).code}, ${intIndex(a.reqObj("high")).code})")
                }
            }
        }
        return null
    }

    fun methodCall(e: Node): Ex {
        val sel = e.reqObj("fun").let { if (it.k == "ParenExpr") it.reqObj("x") else it }
        if (sel.k != "SelectorExpr") refuse("method-call-shape", sel.k)
        val (recv, name, _) = methodTarget(sel)
        val sig = sigOf(e)
        val mo = pc.obj(sel.reqObj("sel").int("obj")!!)
        val shim = mo.str("pkg") != null && mo.str("pkg") !in prog.ported
        return Ex.primary("${recv.at(PRIMARY)}.$name(${args(e, sig, shim && sig.variadic, shim).joinToString(", ")})")
    }

    fun methodExprCall(e: Node): Ex {
        val sel = e.reqObj("fun")
        val mo = pc.obj(sel.reqObj("sel").int("obj")!!)
        val name = prog.methodName(mo.str("key") ?: "", mo.str("name")!!)
        val sig = sigOf(e)
        val a = args(e, sig, false)
        val recvT = sig.params[0].t
        val r = if (tm.nullable(recvT) && mo.str("key") !in prog.extensionMethods) "${a[0]}!!" else a[0]
        return Ex.primary("${Ex(r, if (r.endsWith("!!")) PRIMARY else 0).code}.$name(${a.drop(1).joinToString(", ")})")
    }

    fun dynamicCall(e: Node): Ex {
        val f = e.reqObj("fun")
        val sig = sigOf(e)
        val a = args(e, sig, false).joinToString(", ")
        val callee = raw(f)
        if (f.k == "FuncLit" || (f.k == "ParenExpr" && f.reqObj("x").k == "FuncLit")) return Ex.primary("(${callee.code})($a)")
        return Ex.primary("${callee.at(PRIMARY)}!!($a)")
    }

    // ------------------------------------------------------------------ conversions

    fun convert(e: Node): Ex {
        if (e.str("m") == "const" && e.obj("c") != null) return constant(e)
        val to = e.int("to") ?: ty(e)
        val x = e.list("args").single()
        val tu = types.under(to)
        val fu = types.under(ty(x))
        if (tu is InterfaceType) return lower(x)
        if (tu is BasicType && fu is BasicType) return wrap(convertBasic(raw(x), fu, tu), to)
        if (tu is BasicType && tm.rep(tu) == Rep.STRING) {
            // string([]byte), string([]rune)
            val fe = (fu as? SliceType)?.elem ?: refuse("conv", "${types[ty(x)].key} -> string")
            val kind = types.basic(fe)?.let { tm.kindOf(it) }
            val f = when (kind) {
                "Uint8" -> "goBytesToString"
                "Int32" -> "goRunesToString"
                else -> refuse("conv", "slice -> string")
            }
            return wrap(Ex.primary("$f(${raw(x).code})"), to)
        }
        if (tu is SliceType && fu is BasicType && tm.rep(fu) == Rep.STRING) {
            val kind = types.basic(tu.elem)?.let { tm.kindOf(it) }
            val f = when (kind) {
                "Uint8" -> "goStringToBytes"
                "Int32" -> "goStringToRunes"
                else -> refuse("conv", "string -> slice")
            }
            return wrap(Ex.primary("$f(${raw(x).code})"), to)
        }
        // Named <-> underlying, or between named types of identical underlying type.
        val toVc = tm.isValueClass(to)
        val fromVc = tm.isValueClass(ty(x))
        return when {
            toVc -> wrap(raw(x), to)
            fromVc -> raw(x)
            tu is PointerType || tu is StructType -> {
                // Identical classes, or a struct alias (`type MutableNode Node`, a typealias).
                if (tm.kt(to).removeSuffix("?") != tm.kt(ty(x)).removeSuffix("?") && !isStructAlias(to) && !isStructAlias(ty(x))) {
                    refuse("conv-struct", "${types[ty(x)].key} -> ${types[to].key}")
                }
                lower(x)
            }
            else -> lower(x)
        }
    }

    private fun isStructAlias(t: Int): Boolean {
        var u = types.unalias(t)
        if (u is PointerType) u = types.unalias(u.elem)
        return u is com.xemantic.typescript.goport.types.NamedType && tm.namedKind(u) == TypeMapper.NamedKind.ALIAS
    }

    override fun convertRaw(e: Node): Ex {
        if (e.str("m") == "const" && e.obj("c") != null) return constRaw(e)
        val to = e.int("to") ?: ty(e)
        val x = e.list("args").single()
        val tu = types.under(to)
        val fu = types.under(ty(x))
        if (tu is BasicType && fu is BasicType) return convertBasic(raw(x), fu, tu)
        return unwrap(convert(e))
    }

    fun convertBasic(v: Ex, from: BasicType, to: BasicType): Ex {
        val fr = tm.rep(from)
        val tr = tm.rep(to)
        val fk = tm.kindOf(from)
        val tk = tm.kindOf(to)
        if (tr == Rep.STRING) {
            if (fr == Rep.STRING) return v
            return Ex.primary("goRuneToString(${v.code})")
        }
        if (fr == Rep.STRING) refuse("conv", "string -> $tk")
        fun m(s: String) = Ex.primary("${v.at(PRIMARY)}.$s()")
        val base: Ex = when (tr) {
            Rep.INT -> when (fr) {
                Rep.INT -> v
                Rep.LONG, Rep.UINT, Rep.ULONG -> m("toInt")
                Rep.DOUBLE -> Ex.primary("goFloat64ToInt(${v.code})")
                Rep.FLOAT -> Ex.primary("goFloat64ToInt(${v.at(PRIMARY)}.toDouble())")
                else -> refuse("conv", "$fk -> $tk")
            }
            Rep.LONG -> when (fr) {
                Rep.LONG -> v
                Rep.INT, Rep.UINT, Rep.ULONG -> m("toLong")
                Rep.DOUBLE -> Ex.primary("goFloat64ToInt64(${v.code})")
                else -> refuse("conv", "$fk -> $tk")
            }
            Rep.UINT -> when (fr) {
                Rep.UINT -> v
                Rep.INT, Rep.LONG, Rep.ULONG -> m("toUInt")
                Rep.DOUBLE -> Ex.primary("goFloat64ToUint32(${v.code})")
                else -> refuse("conv", "$fk -> $tk")
            }
            Rep.ULONG -> when (fr) {
                Rep.ULONG -> v
                Rep.INT, Rep.LONG, Rep.UINT -> m("toULong")
                Rep.DOUBLE -> Ex.primary("goFloat64ToUint64(${v.code})")
                else -> refuse("conv", "$fk -> $tk")
            }
            Rep.DOUBLE -> if (fr == Rep.DOUBLE) v else m("toDouble")
            Rep.FLOAT -> if (fr == Rep.FLOAT) v else m("toFloat")
            Rep.BOOL -> if (fr == Rep.BOOL) v else refuse("conv", "$fk -> bool")
            else -> refuse("conv", "$fk -> $tk")
        }
        // Narrow targets: truncate unless the source already fits (same or narrower kind of same signedness).
        val fits = when (tk) {
            "Int8" -> fk == "Int8"
            "Uint8" -> fk == "Uint8"
            "Int16" -> fk in setOf("Int8", "Uint8", "Int16")
            "Uint16" -> fk in setOf("Uint8", "Uint16")
            else -> true
        }
        return if (fits) base else truncate(base, tk)
    }

    // ------------------------------------------------------------------ builtins

    fun builtin(e: Node): Ex {
        val name = e.str("builtin")!!
        val args = e.list("args")
        return when (name) {
            "len" -> {
                val a = args[0]
                if (a.str("m") == "const" && a.obj("c") != null && types.isString(ty(a))) return constant(e)
                val u = types.under(types.core(ty(a)))
                val r = raw(a)
                Ex.primary(when (u) {
                    is BasicType -> "${r.at(PRIMARY)}.length"
                    is SliceType, is MapType -> "${r.at(PRIMARY)}.len"
                    is ArrayType -> "${r.at(PRIMARY)}.size"
                    is PointerType -> "${nn(r).code}.size"
                    else -> refuse("len-of", types[ty(a)].key)
                })
            }
            "cap" -> Ex.primary("${raw(args[0]).at(PRIMARY)}.cap")
            "append" -> {
                val s = args[0]
                val base = raw(s)
                val out = when {
                    args.size == 1 -> base
                    e.bool("spread") -> {
                        val src = args[1]
                        if (types.isString(ty(src))) Ex.primary("goAppendString(${base.code}, ${raw(src).code})")
                        else Ex.primary("${base.at(PRIMARY)}.appendSlice(${raw(src).code})")
                    }
                    args.size == 2 -> Ex.primary("${base.at(PRIMARY)}.append1(${flow(args[1]).code})")
                    else -> Ex.primary("${base.at(PRIMARY)}.append(${args.drop(1).joinToString(", ") { flow(it).code }})")
                }
                wrap(out, ty(e))
            }
            "copy" -> {
                val src = args[1]
                if (types.isString(ty(src))) Ex.primary("goCopyString(${raw(args[0]).code}, ${raw(src).code})")
                else Ex.primary("goCopy(${raw(args[0]).code}, ${raw(src).code})")
            }
            "delete" -> Ex.primary("${raw(args[0]).at(PRIMARY)}.delete(${flow(args[1]).code})")
            "clear" -> {
                val u = types.under(types.core(ty(args[0])))
                if (u is MapType) Ex.primary("${raw(args[0]).at(PRIMARY)}.clear()") else Ex.primary("goClear(${raw(args[0]).code})")
            }
            "make" -> {
                val t = ty(args[0])
                val u = types.under(types.core(t))
                val r = when (u) {
                    is SliceType -> {
                        val n = args.getOrNull(1)?.let { intIndex(it).code } ?: "0"
                        val c = args.getOrNull(2)?.let { ", " + intIndex(it).code } ?: ""
                        "GoSlice.make(${tm.elem(u.elem)}, $n$c)"
                    }
                    is MapType -> {
                        val hint = args.getOrNull(1)?.let { ", " + intIndex(it).code } ?: ""
                        "GoMap.make<${tm.kt(u.keyType)}, ${tm.kt(u.elem)}>(${tm.elem(u.elem)}$hint)"
                    }
                    else -> refuse("make", types[t].key)
                }
                wrap(Ex.primary(r), t)
            }
            "new" -> {
                val t = ty(args[0])
                when {
                    tm.isStructValue(t) -> Ex.primary(tm.zero(t))
                    // `new(T)` is a `*T`, which for a type parameter IS a `T` (design § 3: a pointer to a
                    // struct is the reference); its zero is the element kind's zero.
                    tm.opaqueTP(t) -> Ex.primary(tm.zero(t))
                    else -> Ex.primary("goNew(${tm.elem(t)})")
                }
            }
            "panic" -> Ex.primary("goPanic(${flow(args[0]).code})")
            "min", "max" -> {
                val f = if (name == "min") "minOf" else "maxOf"
                val rep = tm.repOf(ty(e))
                val fname = if (rep == Rep.DOUBLE || rep == Rep.FLOAT) (if (name == "min") "goMin" else "goMax") else f
                val r = args.map { raw(it).code }
                val call = if (r.size == 1) r[0] else "$fname(${r.joinToString(", ")})"
                wrap(Ex.primary(call), ty(e))
            }
            "unsafe.String" -> {
                // unsafe.String(&b[0], n) / (unsafe.SliceData(b), len(b)): a byte view → a copy.
                refuse("unsafe")
            }
            else -> refuse("builtin", name)
        }
    }

}
