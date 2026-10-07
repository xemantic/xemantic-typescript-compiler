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

package com.xemantic.typescript.goport.types

import com.xemantic.typescript.goport.ir.IrPackage
import com.xemantic.typescript.goport.ir.Node
import com.xemantic.typescript.goport.ir.bool
import com.xemantic.typescript.goport.ir.int
import com.xemantic.typescript.goport.ir.ints
import com.xemantic.typescript.goport.ir.k
import com.xemantic.typescript.goport.ir.list
import com.xemantic.typescript.goport.ir.obj
import com.xemantic.typescript.goport.ir.reqInt
import com.xemantic.typescript.goport.ir.str

/**
 * A Go type of one package's IR type table (docs/goport-ir.md § 8.1). Ids are local to the
 * package file; [key] is the cross-package join key.
 */
sealed class GoType(val id: Int, val key: String)

class BasicType(id: Int, key: String, val name: String, val kind: String, val untyped: Boolean, val defaultId: Int?) :
    GoType(id, key)

class NamedType(
    id: Int, key: String,
    val name: String,
    /** Declaring package path; null for universe `error`/`comparable`. */
    val pkg: String?,
    val localAt: String?,
    val tparams: List<Int>,
    val origin: Int?,
    val targs: List<Int>,
    val underlying: Int,
    val isStruct: Boolean,
    val isIface: Boolean,
    val comparable: Boolean,
    val node: Node,
) : GoType(id, key) {
    val isGenericOrigin: Boolean get() = tparams.isNotEmpty() && origin == null
}

class AliasType(id: Int, key: String, val name: String, val pkg: String?, val actual: Int, val targs: List<Int>) : GoType(id, key)
class PointerType(id: Int, key: String, val elem: Int) : GoType(id, key)
class SliceType(id: Int, key: String, val elem: Int) : GoType(id, key)
class ArrayType(id: Int, key: String, val len: Long, val elem: Int) : GoType(id, key)
class MapType(id: Int, key: String, val keyType: Int, val elem: Int) : GoType(id, key)
class ChanType(id: Int, key: String, val elem: Int) : GoType(id, key)

class Param(val name: String, val t: Int)

class SignatureType(
    id: Int, key: String,
    val params: List<Param>,
    val results: List<Param>,
    val variadic: Boolean,
    val recv: Node?,
    val tparams: List<Int>,
    val recvTparams: List<Int>,
) : GoType(id, key)

class StructField(
    val name: String, val t: Int, val embedded: Boolean, val exported: Boolean, val pkg: String?, val tag: String = "",
)

class StructType(id: Int, key: String, val fields: List<StructField>) : GoType(id, key)

class IfaceMethod(val name: String, val sig: Int, val fn: String?)

class InterfaceType(
    id: Int, key: String,
    val methods: List<IfaceMethod>,
    val embedded: List<Int>,
    val allMethods: List<IfaceMethod>,
    val isMethodSet: Boolean,
    val comparable: Boolean,
) : GoType(id, key)

class TypeParamType(id: Int, key: String, val name: String, val index: Int, val constraint: Int, val core: Int?) :
    GoType(id, key)

class TupleType(id: Int, key: String, val elems: List<Param>) : GoType(id, key)
class UnionType(id: Int, key: String, val terms: List<Pair<Boolean, Int>>) : GoType(id, key)

/** A method-set entry (`msetT` / `msetPtr`). */
class MethodSetEntry(
    val name: String, val fn: String, val path: List<Int>, val indirect: Boolean, val ptrRecv: Boolean, val sig: Int,
)

/** One package's type table with the queries the lowering asks. */
class TypeTable(val pkg: IrPackage) {

    private val cache = arrayOfNulls<GoType>(pkg.types.size)

    operator fun get(id: Int): GoType {
        cache[id]?.let { return it }
        val t = build(pkg.types[id])
        cache[id] = t
        return t
    }

    val size: Int get() = pkg.types.size

    private fun params(n: Node, key: String) = n.list(key).map { Param(it.str("name") ?: "", it.reqInt("t")) }

    private fun build(n: Node): GoType {
        val id = n.reqInt("id")
        val key = n.str("key") ?: ""
        return when (n.k) {
            "basic" -> BasicType(id, key, n.str("name")!!, n.str("kind")!!, n.bool("untyped"), n.int("default"))
            "named" -> NamedType(
                id, key, n.str("name")!!, n.str("pkg"), n.str("localAt"), n.ints("tparams"), n.int("origin"),
                n.ints("targs"), n.reqInt("u"), n.bool("isStruct"), n.bool("isIface"), n.bool("comparable"), n,
            )
            "alias" -> AliasType(id, key, n.str("name")!!, n.str("pkg"), n.reqInt("actual"), n.ints("targs"))
            "pointer" -> PointerType(id, key, n.reqInt("elem"))
            "slice" -> SliceType(id, key, n.reqInt("elem"))
            "array" -> ArrayType(id, key, n.str("len")!!.toLong(), n.reqInt("elem"))
            "map" -> MapType(id, key, n.reqInt("keyType"), n.reqInt("elem"))
            "chan" -> ChanType(id, key, n.reqInt("elem"))
            "signature" -> SignatureType(
                id, key, params(n, "params"), params(n, "results"), n.bool("variadic"), n.obj("recv"),
                n.ints("tparams"), n.ints("recvTparams"),
            )
            "struct" -> StructType(id, key, n.list("fields").map {
                StructField(it.str("name")!!, it.reqInt("t"), it.bool("embedded"), it.bool("exported"), it.str("pkg"), it.str("tag") ?: "")
            })
            "interface" -> InterfaceType(
                id, key,
                n.list("methods").map { IfaceMethod(it.str("name")!!, it.reqInt("sig"), it.str("fn")) },
                n.ints("embedded"),
                n.list("allMethods").map { IfaceMethod(it.str("name")!!, it.reqInt("sig"), it.str("fn")) },
                n.bool("isMethodSet"), n.bool("comparable"),
            )
            "typeparam" -> TypeParamType(id, key, n.str("name")!!, n.reqInt("index"), n.reqInt("constraint"), n.int("core"))
            "tuple" -> TupleType(id, key, params(n, "elems"))
            "union" -> UnionType(id, key, n.list("terms").map { it.bool("tilde") to it.reqInt("t") })
            else -> error("unknown type kind ${n.k}")
        }
    }

    /** Follows aliases. */
    fun unalias(id: Int): GoType {
        var t = get(id)
        while (t is AliasType) t = get(t.actual)
        return t
    }

    /** The underlying type: through aliases and named types. */
    fun under(id: Int): GoType {
        var t = unalias(id)
        while (t is NamedType) t = unalias(t.underlying)
        return t
    }

    fun underId(id: Int): Int = under(id).id

    fun isBasic(id: Int): Boolean = under(id) is BasicType

    fun basic(id: Int): BasicType? = under(id) as? BasicType

    /** A struct value type (named or anonymous), not `struct{}`. */
    fun isStruct(id: Int): Boolean = under(id) is StructType

    fun isInterface(id: Int): Boolean = under(id) is InterfaceType

    fun isPointer(id: Int): Boolean = under(id) is PointerType

    fun isTypeParam(id: Int): Boolean = unalias(id) is TypeParamType

    fun isString(id: Int): Boolean = basic(id)?.let { it.kind == "String" || it.kind == "UntypedString" } ?: false

    /** The core type of a type parameter, else the type itself. */
    fun core(id: Int): Int {
        val t = unalias(id)
        if (t is TypeParamType) return t.core ?: id
        return id
    }

    fun msetPtr(named: NamedType): List<MethodSetEntry> = mset(named.node, "msetPtr")
    fun msetT(named: NamedType): List<MethodSetEntry> = mset(named.node, "msetT")

    private fun mset(n: Node, key: String): List<MethodSetEntry> = n.list(key).map {
        MethodSetEntry(it.str("name")!!, it.str("fn")!!, it.ints("path"), it.bool("indirect"), it.bool("ptrRecv"), it.reqInt("sig"))
    }
}
