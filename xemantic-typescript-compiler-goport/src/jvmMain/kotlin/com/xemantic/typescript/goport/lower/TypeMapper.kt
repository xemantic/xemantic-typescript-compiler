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

import com.xemantic.typescript.goport.naming.Naming
import com.xemantic.typescript.goport.types.AliasType
import com.xemantic.typescript.goport.types.ArrayType
import com.xemantic.typescript.goport.types.BasicType
import com.xemantic.typescript.goport.types.ChanType
import com.xemantic.typescript.goport.types.InterfaceType
import com.xemantic.typescript.goport.types.MapType
import com.xemantic.typescript.goport.types.NamedType
import com.xemantic.typescript.goport.types.PointerType
import com.xemantic.typescript.goport.types.SignatureType
import com.xemantic.typescript.goport.types.SliceType
import com.xemantic.typescript.goport.types.StructType
import com.xemantic.typescript.goport.types.TupleType
import com.xemantic.typescript.goport.types.TypeParamType
import com.xemantic.typescript.goport.types.UnionType

/**
 * Go type → Kotlin type, zero value and element kind (docs/goport-design.md § 3).
 *
 * [tpNames] renames type parameters by id (a method's receiver type parameters are the class's).
 */
class TypeMapper(
    val fc: FileCtx,
    val tpNames: Map<Int, String> = emptyMap(),
    /** The `GoElem<T>` expression of each type parameter in scope (a generic class's dictionary fields). */
    val tpElems: Map<Int, String> = emptyMap(),
) {

    /** The dictionary parameter a generic struct class carries for type parameter [name]. */
    fun elemParam(name: String): String = "goElem_" + name.trim('`')

    /** `goElem_T = <elem of arg>` arguments for constructing generic struct [t]. */
    fun dictArgs(t: NamedType): List<String> {
        val origin = t.origin?.let { types.unalias(it) as NamedType } ?: t
        if (!isPortedNamed(t) || origin.tparams.isEmpty()) return emptyList()
        val args = if (t.targs.isEmpty()) origin.tparams else t.targs
        return origin.tparams.indices.filter { !isSubstituted(origin.tparams[it]) }.map { i ->
            "${elemParam((types.unalias(origin.tparams[i]) as TypeParamType).name)} = ${elem(args[i])}"
        }
    }

    val pc get() = fc.pc
    val types get() = pc.types

    /** The Kotlin representation category of a basic kind. */
    enum class Rep { BOOL, INT, UINT, LONG, ULONG, DOUBLE, FLOAT, STRING, UNSAFE }

    fun rep(b: BasicType): Rep {
        val k = if (b.untyped) types.basic(b.defaultId ?: refuse("untyped-no-default", b.name))!!.kind else b.kind
        return when (k) {
            "Bool", "UntypedBool" -> Rep.BOOL
            "Int", "Int8", "Int16", "Int32", "Uint8", "Uint16", "UntypedInt", "UntypedRune" -> Rep.INT
            "Uint32" -> Rep.UINT
            "Int64" -> Rep.LONG
            "Uint", "Uint64", "Uintptr" -> Rep.ULONG
            "Float64", "UntypedFloat" -> Rep.DOUBLE
            "Float32" -> Rep.FLOAT
            "String", "UntypedString" -> Rep.STRING
            "UnsafePointer" -> Rep.UNSAFE
            else -> refuse("basic-kind", k)
        }
    }

    /** The concrete (defaulted) basic kind name. */
    fun kindOf(b: BasicType): String = if (b.untyped) types.basic(b.defaultId ?: refuse("untyped-no-default", b.name))!!.kind else b.kind

    fun repOf(id: Int): Rep? = types.basic(id)?.let { rep(it) }

    fun basicKt(r: Rep): String = when (r) {
        Rep.BOOL -> "Boolean"
        Rep.INT -> "Int"
        Rep.UINT -> "UInt"
        Rep.LONG -> "Long"
        Rep.ULONG -> "ULong"
        Rep.DOUBLE -> "Double"
        Rep.FLOAT -> "Float"
        Rep.STRING -> "String"
        Rep.UNSAFE -> "Any?"
    }

    // ---- classification of named types ----

    enum class NamedKind { STRUCT, IFACE, CONSTRAINT, VALUE, ALIAS, ERROR }

    fun namedKind(n: NamedType): NamedKind {
        if (n.pkg == null) return if (n.name == "error") NamedKind.ERROR else NamedKind.CONSTRAINT
        val u = types.under(n.id)
        val origin = n.origin?.let { types.unalias(it) as NamedType } ?: n
        return when (u) {
            is StructType -> if (origin.key in pc.prog.structAliases) NamedKind.ALIAS else NamedKind.STRUCT
            // A named EMPTY interface (`type TypeSystemEntity any`) is `Any?` (a typealias): every value implements it.
            is InterfaceType -> if (!u.isMethodSet) NamedKind.CONSTRAINT
                else if (u.allMethods.isEmpty() && u.methods.isEmpty() && u.embedded.isEmpty()) NamedKind.ALIAS else NamedKind.IFACE
            is BasicType -> NamedKind.VALUE
            // A named slice/map/func type is assignable from its unnamed underlying type without
            // a conversion, and the IR records none: a Kotlin typealias keeps that assignability.
            else -> NamedKind.ALIAS
        }
    }

    /** A named type whose Kotlin form is a value class (named non-struct, non-interface). */
    fun isValueClass(id: Int): Boolean {
        val t = types.unalias(id)
        return t is NamedType && namedKind(t) == NamedKind.VALUE
    }

    fun isStructValue(id: Int): Boolean = types.under(id) is StructType

    fun isEmptyStruct(id: Int): Boolean = (types.under(id) as? StructType)?.fields?.isEmpty() ?: false

    /** Kotlin class reference of a named type (no type arguments, no nullability). */
    fun namedRef(n: NamedType): String {
        if (n.pkg == null) return if (n.name == "error") "GoError" else refuse("universe-type", n.name)
        val origin = if (n.origin != null) types.unalias(n.origin) as NamedType else n
        if (n.localAt != null) {
            // A function-local type, hoisted to the package top level (Program.localTypeQnames).
            val q = pc.prog.localTypeQnames[origin.key] ?: refuse("local-type", n.name)
            if (n.targs.isNotEmpty() || origin.tparams.isNotEmpty()) refuse("local-type-generic", n.name)
            return "${Naming.kotlinPackage(origin.pkg!!)}.${pc.prog.localTypeNames.getValue(q)}"
        }
        val kpkg = Naming.kotlinPackage(origin.pkg!!)
        val name = if (origin.pkg in pc.prog.ported) pc.prog.typeName(origin.key, origin.name) else origin.name
        if (origin.pkg !in pc.prog.ported && !pc.prog.shims.hasTop(kpkg, name)) refuse("shim-missing", "${origin.pkg}.$name")
        // An unexported Go type (`tempFlags`) is spelled like a field or local of the same name, which
        // shadows it in expression position (`tempFlags.ELEM`, `tempFlags(0)`): always qualify it.
        if (name[0].isLowerCase()) return "$kpkg.$name"
        return fc.typeRef(kpkg, name)
    }

    fun typeArgs(n: NamedType): String {
        val origin = n.origin?.let { types.unalias(it) as NamedType } ?: n
        val args = if (n.targs.isEmpty()) n.tparams.filter { !isSubstituted(it) } else keptTypeArgs(origin.tparams, n.targs)
        return if (args.isEmpty()) "" else "<" + args.joinToString(", ") { kt(it) } + ">"
    }

    /** The Kotlin type of a Go type in a value position. */
    fun kt(id: Int): String {
        shimAlias(id)?.let { return it + if (nullable(id)) "?" else "" }
        val t = types.unalias(id)
        return when (t) {
            is BasicType -> basicKt(rep(t))
            is NamedType -> when (namedKind(t)) {
                NamedKind.ERROR -> "GoError?"
                NamedKind.IFACE -> namedRef(t) + typeArgs(t) + "?"
                NamedKind.CONSTRAINT -> refuse("constraint-as-type", t.name)
                NamedKind.STRUCT, NamedKind.VALUE -> namedRef(t) + typeArgs(t)
                // A SHIM typealias of a func type is non-null (`iter.Seq`); a ported one already includes `?`.
                NamedKind.ALIAS -> if (types.under(t.id) is StructType) namedRef(t) + typeArgs(t)
                    else namedRef(t) + typeArgs(t) + if (!isPortedNamed(t) && nullable(t.underlying)) "?" else ""
            }
            is PointerType -> {
                when {
                    // A pointer to a struct or ARRAY is the (mutable) reference itself (design § 3).
                    types.under(t.elem) is StructType || types.under(t.elem) is ArrayType -> kt(t.elem) + "?"
                    // `*T` for an opaque T: T stands for the reference (design § 3), and Go's nil pointer is null.
                    opaqueTP(t.elem) -> kt(t.elem).removeSuffix("?") + "?"
                    else -> "GoPtr<${kt(t.elem)}>?"
                }
            }
            is SliceType -> "GoSlice<${kt(t.elem)}>"
            is ArrayType -> "GoArray<${kt(t.elem)}>"
            is MapType -> "GoMap<${kt(t.keyType)}, ${kt(t.elem)}>"
            is ChanType -> refuse("chan")
            is SignatureType -> "(${funType(t)})?"
            is StructType -> if (t.fields.isEmpty()) "Unit" else anonStruct(t)
            is InterfaceType -> ifaceKt(t) ?: "Any?"
            is TypeParamType -> substituted(t)?.let { kt(it) } ?: tpName(t)
            is TupleType -> tupleKt(t.elems.map { it.t })
            is UnionType -> refuse("union-as-type")
            is AliasType -> error("unreachable")
        }
    }

    /**
     * A type alias declared by a NON-ported package whose shim declares the alias name: the shim's
     * name, not the aliased type (`json.Options = jsonopts.Options` lives in an internal package the
     * shims do not mirror).
     */
    private fun shimAlias(id: Int): String? {
        val a = types[id] as? AliasType ?: return null
        val pkg = a.pkg ?: return null
        if (pkg in pc.prog.ported || a.targs.isNotEmpty()) return null
        val kp = Naming.kotlinPackage(pkg)
        if (!pc.prog.shims.hasTop(kp, a.name)) return null
        return fc.typeRef(kp, a.name)
    }

    /** The `<Name>_Box` class of a named type in [Program.boxedNamed], or null. */
    fun boxOf(id: Int): String? {
        val t = types.unalias(id) as? NamedType ?: return null
        if (t.key !in pc.prog.boxedNamed) return null
        return fc.typeRef(Naming.kotlinPackage(t.pkg!!), pc.prog.typeName(t.key, t.name) + "_Box")
    }

    /**
     * A `GoTypeInfo` expression describing Go type [id] to the codegen `reflect` (runtime GoReflect.kt):
     * kind, name, Kotlin class, element/key types, struct fields (via the struct's `GO_STRUCT`), zero.
     */
    fun reflectTypeInfo(id: Int): String {
        val t = types.unalias(id)
        val zero = try { "{ ${zero(id)} }" } catch (_: Refusal) { "{ null }" }
        fun info(kind: Int, name: String = "", cls: String? = null, elem: String? = null, key: String? = null, struct: String? = null) =
            "GoTypeInfo($kind, \"$name\"" + (cls?.let { ", cls = $it" } ?: "") + (elem?.let { ", elem = $it" } ?: "") +
                (key?.let { ", key = $it" } ?: "") + (struct?.let { ", structInfo = { $it }" } ?: "") + ", zero = $zero)"
        return when (t) {
            is BasicType -> info(basicKindNumber(kindOf(t)), kindOf(t).lowercase())
            is NamedType -> {
                val origin = t.origin?.let { types.unalias(it) as NamedType } ?: t
                val qn = "${origin.pkg?.substringAfterLast('/') ?: ""}.${origin.name}"
                when (val u = types.under(t.id)) {
                    is BasicType -> info(basicKindNumber(kindOf(u)), qn, if (namedKind(t) == NamedKind.VALUE) namedRef(t) + "::class" else null)
                    is StructType -> info(25, qn, namedRef(t) + "::class",
                        struct = if (isPortedNamed(t) && origin.key in pc.prog.reflectStructs && namedKind(t) == NamedKind.STRUCT) namedRef(t) + ".GO_STRUCT" else null)
                    is InterfaceType -> info(20, qn)
                    else -> reflectTypeInfo(t.underlying)
                }
            }
            is PointerType -> info(22, elem = reflectTypeInfo(t.elem))
            is SliceType -> info(23, elem = reflectTypeInfo(t.elem))
            is ArrayType -> info(17, elem = reflectTypeInfo(t.elem))
            is MapType -> info(21, elem = reflectTypeInfo(t.elem), key = reflectTypeInfo(t.keyType))
            is SignatureType -> info(19)
            is InterfaceType -> info(20)
            is StructType -> info(25)
            is ChanType -> info(18)
            else -> info(0)
        }
    }

    private fun basicKindNumber(k: String): Int = when (k) {
        "Bool" -> 1; "Int" -> 2; "Int8" -> 3; "Int16" -> 4; "Int32" -> 5; "Int64" -> 6; "Uint" -> 7; "Uint8" -> 8
        "Uint16" -> 9; "Uint32" -> 10; "Uint64" -> 11; "Uintptr" -> 12; "Float32" -> 13; "Float64" -> 14; "String" -> 24
        "UnsafePointer" -> 26
        else -> 0
    }

    fun tpName(t: TypeParamType): String = tpNames[t.id] ?: Naming.escape(t.name)

    /**
     * A type parameter whose constraint has a COMPOSITE core type (`S ~[]E`, `M ~map[K]V`) is
     * replaced by that core type: Kotlin cannot index, range or append an opaque `S`, and the
     * shims follow the same rule (`slices.Clone[S ~[]E, E]` is `clone<E>(s: GoSlice<E>)`).
     */
    fun substituted(t: TypeParamType): Int? {
        val c = t.core ?: return null
        return when (types.unalias(c)) {
            is SliceType, is MapType, is PointerType, is SignatureType, is ChanType, is ArrayType -> c
            else -> null
        }
    }

    fun isSubstituted(id: Int): Boolean = (types.unalias(id) as? TypeParamType)?.let { substituted(it) != null } ?: false

    /** A type parameter that stays a Kotlin type parameter. */
    fun opaqueTP(id: Int): Boolean = (types.unalias(id) as? TypeParamType)?.let { substituted(it) == null } ?: false

    /** Type arguments for [tparams] (generic origin) given [targs]: substituted parameters are dropped. */
    fun keptTypeArgs(tparams: List<Int>, targs: List<Int>): List<Int> =
        targs.filterIndexed { i, _ -> i >= tparams.size || !isSubstituted(tparams[i]) }

    /** A Kotlin function type for a Go signature (without nullability/parentheses). */
    fun funType(s: SignatureType): String {
        val ps = s.params.mapIndexed { i, p ->
            if (s.variadic && i == s.params.lastIndex) kt(p.t) else kt(p.t)
        }
        return "(${ps.joinToString(", ")}) -> ${resultKt(s.results.map { it.t })}"
    }

    /** `: R` after a declaration's parameter list; nothing for no results (Kotlin flags `: Unit` as redundant). */
    fun returns(results: List<Int>): String = if (results.isEmpty()) "" else ": " + resultKt(results)

    fun resultKt(results: List<Int>): String = when (results.size) {
        0 -> "Unit"
        1 -> kt(results[0])
        else -> tupleKt(results)
    }

    fun tupleKt(ids: List<Int>): String {
        if (ids.size !in 2..5) refuse("tuple-arity", "${ids.size}")
        return "Tuple${ids.size}<${ids.joinToString(", ") { kt(it) }}>"
    }

    /**
     * An anonymous struct type (`var state struct{ parent *Node; visit func(*Node) bool }`): one
     * synthetic class per distinct type per package, `AnonStruct_<hash>`, with Go value semantics
     * (goCopy/goSet) and structural equality (Go compares struct values field by field).
     */
    fun anonStruct(t: StructType): String {
        val name = "AnonStruct_" + Integer.toHexString(t.key.hashCode()).padStart(8, '0')
        val reg = pc.prog.anonStructs.getOrPut(pc.pkg.path) { LinkedHashMap() }
        if (name !in reg) {
            reg[name] = "" // reserve (recursive field types)
            val mt = TypeMapper(SynthFileCtx(pc))
            val fs = t.fields.mapIndexed { i, f -> pc.prog.fieldName(null, f.name, i) to f }
            for ((_, f) in fs) {
                var tpRef = false
                com.xemantic.typescript.goport.lower.Program.walkTypes(types, f.t) { tpRef = tpRef || it is TypeParamType }
                if (tpRef) refuse("anon-struct-generic")
            }
            reg[name] = buildString {
                append("// go: anonymous struct ${t.key.take(160)}\n")
                append("class $name(\n")
                for ((n, f) in fs) {
                    // A value-class field cannot be @JvmField (as in struct classes: a JvmName'd accessor).
                    val vc = mt.isValueClass(f.t) || mt.repOf(f.t).let { it == Rep.UINT || it == Rep.ULONG }
                    val ann = if (vc) "@get:kotlin.jvm.JvmName(\"goGet_${n.trim('`')}\") @set:kotlin.jvm.JvmName(\"goSet_${n.trim('`')}\")" else "@kotlin.jvm.JvmField"
                    append("    $ann var $n: ${mt.kt(f.t)} = ${mt.zero(f.t)},\n")
                }
                append(") {\n")
                append("    fun goCopy(): $name = $name(${fs.joinToString(", ") { (n, f) -> "$n = " + if (mt.isStructValue(f.t) && !mt.isEmptyStruct(f.t)) "$n.goCopy()" else n }})\n\n")
                append("    fun goSet(o: $name) {\n")
                for ((n, f) in fs) append("        $n = o.$n${if (mt.isStructValue(f.t) && !mt.isEmptyStruct(f.t)) ".goCopy()" else ""}\n")
                append("    }\n\n")
                append("    override fun equals(other: Any?): Boolean = other is $name && ${fs.joinToString(" && ") { (n, _) -> "$n == other.$n" }}\n\n")
                append("    override fun hashCode(): Int = ${fs.joinToString(" + ") { (n, _) -> "31 * $n.hashCode()" }}\n")
                append("}\n")
            }
        }
        return fc.typeRef(pc.kotlinPackage, name)
    }

    /** An anonymous interface: `Any?` when empty, else its synthetic nominal interface. */
    fun ifaceKt(t: InterfaceType): String? {
        if (!t.isMethodSet) refuse("constraint-as-type")
        if (t.allMethods.isEmpty() && t.methods.isEmpty()) return null
        return synthIface(t) + "?"
    }

    fun synthIface(t: InterfaceType): String {
        val methods = t.allMethods.ifEmpty { t.methods }
        // Rendered with fully qualified types, so the text is canonical: identical Go types (which
        // ignore parameter names) map to one interface, and the synth file needs no imports.
        val mt = TypeMapper(SynthFileCtx(pc))
        val sigs = methods.map { m ->
            val s = types.unalias(m.sig) as SignatureType
            val ps = s.params.mapIndexed { i, p -> "p$i: ${mt.kt(p.t)}" }
            "fun ${pc.prog.methodName(m.fn ?: "", m.name)}(${ps.joinToString(", ")})${mt.returns(s.results.map { it.t })}"
        }
        val name = pc.prog.synth.name(sigs.joinToString(";"), methods.map { it.name })
        pc.prog.synth.bodies.getOrPut(name) {
            "interface $name {\n" + sigs.joinToString("") { "    $it\n" } + "}\n"
        }
        return fc.typeRef(Naming.SYNTH, name)
    }

    // ---- zero values and element kinds ----

    fun zero(id: Int): String {
        val t = types.unalias(id)
        return when (t) {
            is BasicType -> zeroBasic(rep(t))
            is NamedType -> when (namedKind(t)) {
                NamedKind.ERROR, NamedKind.IFACE -> "null"
                NamedKind.CONSTRAINT -> refuse("constraint-as-type", t.name)
                NamedKind.STRUCT -> {
                    if (!isPortedNamed(t) && pc.prog.shims.needsArgs(Naming.kotlinPackage(t.pkg!!), t.name)) refuse("shim-zero", "${t.pkg}.${t.name}")
                    namedRef(t) + typeArgs(t) + "(" + dictArgs(t).joinToString(", ") + ")"
                }
                NamedKind.VALUE -> namedRef(t) + typeArgs(t) + "(" + zero(t.underlying) + ")"
                NamedKind.ALIAS -> if (types.under(t.id) is StructType) {
                    val target = pc.prog.structAliasTarget(t)
                    if (target != null && pc.prog.shims.needsArgs(target.first, target.second)) refuse("shim-zero", target.second)
                    namedRef(t) + typeArgs(t) + "()"
                } else zero(t.underlying)
            }
            is PointerType -> "null"
            is SignatureType, is InterfaceType, is ChanType -> "null"
            is SliceType -> elem(t.elem) + ".nilSlice"
            is MapType -> "GoMap.nil<${kt(t.keyType)}, ${kt(t.elem)}>(${elem(t.elem)})"
            is ArrayType -> "GoArray(${t.len}, ${elem(t.elem)})"
            is StructType -> if (t.fields.isEmpty()) "Unit" else anonStruct(t) + "()"
            is TypeParamType -> substituted(t)?.let { zero(it) } ?: tpElems[t.id]?.let { "$it.zeroValue()" } ?: "goZeroTP<${tpName(t)}>()"
            is TupleType -> refuse("tuple-zero")
            is UnionType -> refuse("union-as-type")
            is AliasType -> error("unreachable")
        }
    }

    fun zeroBasic(r: Rep): String = when (r) {
        Rep.BOOL -> "false"
        Rep.INT -> "0"
        Rep.UINT -> "0u"
        Rep.LONG -> "0L"
        Rep.ULONG -> "0uL"
        Rep.DOUBLE -> "0.0"
        Rep.FLOAT -> "0.0f"
        Rep.STRING -> "\"\""
        Rep.UNSAFE -> "null"
    }

    /** A `GoElem<kt(id)>` expression. */
    fun elem(id: Int): String {
        val t = types.unalias(id)
        return when (t) {
            is BasicType -> when (rep(t)) {
                Rep.BOOL -> "GoElem.BOOL"
                Rep.INT -> "GoElem.INT"
                Rep.UINT -> "GoElem.UINT"
                Rep.LONG -> "GoElem.LONG"
                Rep.ULONG -> "GoElem.ULONG"
                Rep.DOUBLE -> "GoElem.DOUBLE"
                Rep.FLOAT -> "GoElem.FLOAT"
                Rep.STRING -> "GoElem.STRING"
                Rep.UNSAFE -> "GoElem.ref<Any?>()"
            }
            is NamedType -> when (namedKind(t)) {
                NamedKind.ERROR, NamedKind.IFACE -> "GoElem.ref<${kt(id)}>()"
                NamedKind.CONSTRAINT -> refuse("constraint-as-type", t.name)
                NamedKind.ALIAS -> when (types.under(t.id)) {
                    is StructType -> "GoElem<${kt(id)}>({ ${zero(id)} }${if (hasGoCopy(t)) ", { it.goCopy() }" else ""})"
                    is SliceType, is MapType, is ArrayType -> elem(t.underlying)
                    else -> "GoElem.ref<${kt(id)}>()"
                }
                NamedKind.STRUCT, NamedKind.VALUE -> {
                    val ported = (if (t.origin != null) (types.unalias(t.origin) as NamedType).pkg else t.pkg) in pc.prog.ported
                    val ref = namedRef(t)
                    when {
                        !ported && namedKind(t) == NamedKind.STRUCT -> "GoElem<${kt(id)}>({ ${zero(id)} }${if (hasGoCopy(t)) ", { it.goCopy() }" else ""})"
                        !ported -> "GoElem({ ${zero(id)} })"
                        t.targs.isNotEmpty() || t.tparams.isNotEmpty() ->
                            "$ref.elem${typeArgs(t)}(${dictArgs(t).joinToString(", ") { it.substringAfter(" = ") }})"
                        else -> "$ref.ELEM"
                    }
                }
            }
            is TypeParamType -> substituted(t)?.let { elem(it) } ?: tpElems[t.id] ?: "GoElem.ref<${kt(id)}>()"
            is PointerType, is SignatureType, is InterfaceType, is ChanType -> "GoElem.ref<${kt(id)}>()"
            is SliceType -> "GoElem.slice(${elem(t.elem)})"
            is MapType -> "GoElem.map<${kt(t.keyType)}, ${kt(t.elem)}>(${elem(t.elem)})"
            is ArrayType -> "GoElem<${kt(id)}>({ GoArray(${t.len}, ${elem(t.elem)}) }, { it.goCopy() })"
            is StructType -> if (t.fields.isEmpty()) "goUnitElem" else "GoElem<${anonStruct(t)}>({ ${anonStruct(t)}() }, { it.goCopy() })"
            is TupleType -> refuse("tuple-elem")
            is UnionType -> refuse("union-as-type")
            is AliasType -> error("unreachable")
        }
    }

    fun isPortedNamed(t: NamedType): Boolean {
        val origin = t.origin?.let { types.unalias(it) as NamedType } ?: t
        return origin.pkg in pc.prog.ported
    }

    /** Whether struct type [id]'s Kotlin class has `goCopy()` (generated classes always do; shims may not). */
    fun hasGoCopy(id: Int): Boolean {
        if (id < 0) return true
        val t = types.unalias(id) as? NamedType ?: return true
        return hasGoCopy(t)
    }

    fun hasGoCopy(t: NamedType): Boolean {
        val origin = t.origin?.let { types.unalias(it) as NamedType } ?: t
        if (origin.pkg in pc.prog.ported) {
            val target = pc.prog.structAliasTarget(origin) ?: return true
            return pc.prog.shims.classHas(target.first, target.second, "goCopy")
        }
        if (origin.pkg == null) return true
        return pc.prog.shims.classHas(Naming.kotlinPackage(origin.pkg), origin.name, "goCopy")
    }

    /** True when [id]'s Kotlin form is nullable (zero is `null`). */
    fun nullable(id: Int): Boolean {
        val t = types.unalias(id)
        return when (t) {
            is NamedType -> namedKind(t) == NamedKind.IFACE || namedKind(t) == NamedKind.ERROR ||
                (namedKind(t) == NamedKind.ALIAS && types.under(t.id) !is StructType && nullable(t.underlying))
            is PointerType -> true
            is TypeParamType -> substituted(t)?.let { nullable(it) } ?: false
            is SignatureType, is ChanType -> true
            is InterfaceType -> true
            is BasicType -> rep(t) == Rep.UNSAFE
            else -> false
        }
    }

    /** The constraint of a type parameter as a Kotlin upper bound, or null for none. */
    fun bound(tp: TypeParamType): String? {
        val c = types.unalias(tp.constraint)
        val iface = when (c) {
            is NamedType -> {
                if (c.pkg == null) return null // comparable / any
                val u = types.under(c.id) as? InterfaceType ?: return null
                if (u.isMethodSet) return if (u.allMethods.isEmpty()) null else kt(c.id)
                u
            }
            is InterfaceType -> c
            else -> return null
        }
        if (iface.isMethodSet) return if (iface.allMethods.isEmpty() && iface.methods.isEmpty()) null else ifaceKt(iface)
        // A type set: Comparable when every term is ordered (numbers or strings).
        val terms = typeSetTerms(iface) ?: return null
        val allOrdered = terms.all { id -> types.basic(id)?.let { rep(it) != Rep.BOOL && rep(it) != Rep.UNSAFE } ?: false }
        return if (allOrdered && terms.isNotEmpty()) "Comparable<${tpName(tp)}>" else null
    }

    private fun typeSetTerms(i: InterfaceType): List<Int>? {
        val out = ArrayList<Int>()
        for (e in i.embedded) {
            when (val t = types.unalias(e)) {
                is UnionType -> out += t.terms.map { it.second }
                is BasicType -> out += e
                is NamedType -> {
                    val u = types.under(e)
                    if (u is InterfaceType) out += typeSetTerms(u) ?: return null else out += e
                }
                is InterfaceType -> out += typeSetTerms(t) ?: return null
                else -> return null
            }
        }
        return out
    }

    fun typeParamDecl(ids: List<Int>): String {
        val kept = ids.filter { !isSubstituted(it) }
        if (kept.isEmpty()) return ""
        return "<" + kept.joinToString(", ") { id ->
            val tp = types.unalias(id) as TypeParamType
            val b = bound(tp)
            if (b == null) tpName(tp) else "${tpName(tp)} : $b"
        } + ">"
    }
}

/** The synth file's context: writes every non-local type fully qualified. */
class SynthFileCtx(pc: PkgCtx) : FileCtx(pc) {
    override fun typeRef(kotlinPackage: String, name: String): String = "$kotlinPackage.$name"
}
