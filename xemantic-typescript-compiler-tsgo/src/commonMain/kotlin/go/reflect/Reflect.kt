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

package com.xemantic.typescript.tsgo.go.reflect

import com.xemantic.typescript.tsgo.runtime.GoBasicValue
import com.xemantic.typescript.tsgo.runtime.GoMap
import com.xemantic.typescript.tsgo.runtime.GoPtr
import com.xemantic.typescript.tsgo.runtime.GoReflectStruct
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.GoTypeInfo
import com.xemantic.typescript.tsgo.runtime.goPanic

// `reflect` by codegen (docs/goport-runtime.md § 11): the porter emits a GoTypeInfo for every static
// type `reflect.TypeFor` names and makes the structs that reach `reflect`/`json` GoReflectStructs;
// this file answers Go's reflect API from those. Only what tsgo's compiler closure calls exists.

/** `reflect.Kind` (Go's numbering: `String` is 24, `Struct` 25, …). */
@kotlin.jvm.JvmInline
value class Kind(val value: ULong) {
    override fun toString(): String = GoTypeInfo.KIND_NAMES.getOrElse(value.toInt()) { "kind$value" }
}

/** `reflect.StructTag` with `Get`/`Lookup` (Go's conventional `key:"value"` syntax). */
@kotlin.jvm.JvmInline
value class StructTag(val value: String) {
    fun get(key: String): String = lookup(key).first

    fun lookup(key: String): com.xemantic.typescript.tsgo.runtime.Tuple2<String, Boolean> {
        var tag = value
        while (tag.isNotEmpty()) {
            var i = 0
            while (i < tag.length && tag[i] == ' ') i++
            tag = tag.substring(i)
            if (tag.isEmpty()) break
            i = 0
            while (i < tag.length && tag[i] > ' ' && tag[i] != ':' && tag[i] != '"' && tag[i].code != 0x7f) i++
            if (i == 0 || i + 1 >= tag.length || tag[i] != ':' || tag[i + 1] != '"') break
            val name = tag.substring(0, i)
            tag = tag.substring(i + 1)
            i = 1
            while (i < tag.length && tag[i] != '"') {
                if (tag[i] == '\\') i++
                i++
            }
            if (i >= tag.length) break
            val qvalue = tag.substring(0, i + 1)
            tag = tag.substring(i + 1)
            if (key == name) return com.xemantic.typescript.tsgo.runtime.Tuple2(unquote(qvalue), true)
        }
        return com.xemantic.typescript.tsgo.runtime.Tuple2("", false)
    }

    private fun unquote(q: String): String {
        val s = q.substring(1, q.length - 1)
        if ('\\' !in s) return s
        val b = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                i++
                b.append(when (s[i]) { 'n' -> '\n'; 't' -> '\t'; else -> s[i] })
            } else b.append(c)
            i++
        }
        return b.toString()
    }
}

/** `reflect.StructField` (the subset tsgo reads). */
class StructField(
    @kotlin.jvm.JvmField var name: String = "",
    @kotlin.jvm.JvmField var pkgPath: String = "",
    @kotlin.jvm.JvmField var type: Type? = null,
    @get:kotlin.jvm.JvmName("goGet_tag") @set:kotlin.jvm.JvmName("goSet_tag") var tag: StructTag = StructTag(""),
    @kotlin.jvm.JvmField var anonymous: Boolean = false,
) {
    fun isExported(): Boolean = pkgPath.isEmpty()
}

/** `reflect.Type`: a [GoTypeInfo]. Equal types are equal (`reflect.TypeOf(x) == T`). */
class Type(val info: GoTypeInfo) {
    fun kind(): Kind = Kind(info.kind.toULong())
    fun name(): String = info.name.substringAfterLast('.')
    fun string(): String = info.toString()
    fun elem(): Type? = info.elem?.let { Type(it) }
    fun key(): Type? = info.key?.let { Type(it) }
    fun numField(): Int = structOf().fields.size

    fun field(i: Int): StructField {
        val f = structOf().fields[i]
        return StructField(name = f.name, pkgPath = if (f.exported) "" else "unexported", type = Type(f.type), tag = StructTag(f.tag), anonymous = f.embedded)
    }

    private fun structOf() = info.struct ?: goPanic("reflect: Field of non-struct type $info")

    override fun equals(other: Any?): Boolean = other is Type && sameType(info, other.info)
    override fun hashCode(): Int = info.kind
    override fun toString(): String = info.toString()
}

private fun sameType(a: GoTypeInfo?, b: GoTypeInfo?): Boolean {
    if (a === b) return true
    if (a == null || b == null || a.kind != b.kind) return false
    if (a.cls != null && b.cls != null) return a.cls == b.cls && sameType(a.elem, b.elem)
    return a.name == b.name && sameType(a.elem, b.elem) && sameType(a.key, b.key)
}

/** `reflect.Value`: a typed location (or a plain value when not settable). */
class Value(
    private val t: GoTypeInfo? = null,
    private val getter: () -> Any? = { null },
    private val setter: ((Any?) -> Unit)? = null,
) {
    private val ti: GoTypeInfo get() = t ?: goPanic("reflect: call of reflect.Value method on zero Value")

    fun kind(): Kind = Kind((t?.kind ?: 0).toULong())
    fun type(): Type? = t?.let { Type(it) }
    fun isValid(): Boolean = t != null

    /** `v.Interface()`. */
    fun `interface`(): Any? = getter()

    fun elem(): Value = when (ti.kind) {
        GoTypeInfo.KIND_POINTER -> {
            val p = getter() ?: return Value()
            val e = ti.elem!!
            when {
                e.kind == GoTypeInfo.KIND_STRUCT || e.kind == GoTypeInfo.KIND_ARRAY -> Value(e, { p })
                p is GoPtr<*> -> @Suppress("UNCHECKED_CAST") (p as GoPtr<Any?>).let { ptr -> Value(e, { ptr.value }, { ptr.value = it }) }
                else -> Value(e, { p })
            }
        }
        GoTypeInfo.KIND_INTERFACE -> getter()?.let { v -> Value(typeInfoOf(v), { v }) } ?: Value()
        else -> goPanic("reflect: call of reflect.Value.Elem on ${ti} Value")
    }

    fun numField(): Int = (ti.struct ?: structOfValue().goStructInfo()).fields.size

    fun field(i: Int): Value {
        val s = structOfValue()
        val ptr = s.goFieldPtr(i)
        val ft = s.goStructInfo().fields[i].type
        // A value-class field's pointer holds the RAW underlying value (the porter's goFieldPtr): wrap it back.
        val vc = if (ft.cls != null && ft.kind != GoTypeInfo.KIND_STRUCT) ft.zero() as? GoBasicValue else null
        if (vc != null) return Value(ft, { ptr.value?.let { vc.goWithRaw(it) } }, { ptr.value = (it as? GoBasicValue)?.goRaw ?: it })
        return Value(ft, { ptr.value }, { ptr.value = it })
    }

    private fun structOfValue(): GoReflectStruct =
        getter() as? GoReflectStruct ?: goPanic("reflect: Field of a struct that does not reach reflect: $ti (porter rule: GoReflectStruct)")

    fun set(x: Value) {
        (setter ?: goPanic("reflect: reflect.Value.Set using unaddressable value"))(x.getter())
    }

    fun setZero() {
        (setter ?: goPanic("reflect: reflect.Value.SetZero using unaddressable value"))(ti.zero())
    }

    fun isZero(): Boolean = goIsZero(getter(), ti)

    fun isNil(): Boolean = when (val v = getter()) {
        null -> true
        is GoSlice<*> -> v.isNil
        is GoMap<*, *> -> v.isNil
        else -> false
    }

    fun canInt(): Boolean = (t?.kind ?: 0) in 2..6
    fun canUint(): Boolean = (t?.kind ?: 0) in 7..12

    fun int(): Long = when (val v = raw(getter())) {
        is Int -> v.toLong()
        is Long -> v
        is UInt -> v.toLong()
        is ULong -> v.toLong()
        else -> goPanic("reflect: call of reflect.Value.Int on ${ti} Value")
    }

    fun uint(): ULong = when (val v = raw(getter())) {
        is Int -> v.toULong()
        is Long -> v.toULong()
        is UInt -> v.toULong()
        is ULong -> v
        else -> goPanic("reflect: call of reflect.Value.Uint on ${ti} Value")
    }

    fun string(): String = when (val v = raw(getter())) {
        is String -> v
        else -> "<$ti Value>"
    }

    fun bool(): Boolean = raw(getter()) as Boolean
    fun float(): Double = raw(getter()) as Double

    // ---------------------------------------------------------------- (TSGO.4-a): lsproto's struct codec,
    // lsutil's preference (de)serializer.

    /** `v.Fields()` (Go 1.26): each field of a struct value with its [StructField], in declaration order. */
    fun fields(): com.xemantic.typescript.tsgo.go.iter.Seq2<StructField, Value> = { yield ->
        val info = structOfValue().goStructInfo()
        for (i in info.fields.indices) {
            val f = info.fields[i]
            val sf = StructField(name = f.name, pkgPath = if (f.exported) "" else "unexported", type = Type(f.type), tag = StructTag(f.tag), anonymous = f.embedded)
            if (!yield(sf, field(i))) break
        }
    }

    /**
     * `v.Addr()` of an addressable value (a struct field): Go's `*T`. A struct or array T's pointer IS
     * the instance; a pointer-typed field's pointer decodes a fresh pointee as the json shim's pointer
     * fields do; any other T's is a [GoPtr] reading and writing through the field.
     */
    fun addr(): Value {
        val s = setter ?: goPanic("reflect.Value.Addr of unaddressable value")
        val t = ti
        val g = getter
        val ptrInfo = GoTypeInfo.ptr(t)
        return when (t.kind) {
            GoTypeInfo.KIND_STRUCT, GoTypeInfo.KIND_ARRAY -> Value(ptrInfo, g)
            GoTypeInfo.KIND_POINTER -> Value(ptrInfo, { PointeeCell(g, s, t.elem) })
            else -> {
                val cell = object : GoPtr<Any?> {
                    override var value: Any?
                        get() = g()
                        set(v) = s(v)
                }
                Value(ptrInfo, { cell })
            }
        }
    }

    /** `v.Len()` of a slice, map or string. */
    fun len(): Int = when (val v = raw(getter())) {
        is GoSlice<*> -> v.len
        is GoMap<*, *> -> v.len
        is String -> v.length
        else -> goPanic("reflect: call of reflect.Value.Len on $ti Value")
    }

    /** `v.Index(i)` of a slice. */
    fun index(i: Int): Value {
        @Suppress("UNCHECKED_CAST") val s = getter() as GoSlice<Any?>
        val et = ti.elem ?: GoTypeInfo(0)
        return Value(et, { s[i] }, { s[i] = it })
    }

    private fun setRaw(x: Any?) {
        val s = setter ?: goPanic("reflect: reflect.Value.Set using unaddressable value")
        val vc = if (t?.cls != null) t.zero() as? GoBasicValue else null
        s(vc?.goWithRaw(x!!) ?: x)
    }

    fun setBool(x: Boolean) = setRaw(x)

    fun setInt(x: Long) = setRaw(when ((t?.kind ?: 0)) {
        GoTypeInfo.KIND_INT64 -> x
        else -> x.toInt()
    })

    fun setString(x: String) = setRaw(x)
}

/**
 * `Addr()` of a POINTER-typed field (a `**T`) as the json shim decodes into it ((TSGO.4-a): lsproto's
 * `unmarshalStruct`): the shim reads a target's Go type from its CURRENT value, which for a nil `*T` is
 * nothing, so the cell hands out a zero T as that value — a struct's pointer IS the struct, allocated into
 * the field on first read and filled in place; any other T's pointee is boxed into the field when set.
 */
internal class PointeeCell(private val get: () -> Any?, private val set: (Any?) -> Unit, private val elem: GoTypeInfo?) : GoPtr<Any?> {
    private val structLike = elem != null && (elem.kind == GoTypeInfo.KIND_STRUCT || elem.kind == GoTypeInfo.KIND_ARRAY)

    override var value: Any?
        get() {
            if (structLike) {
                get()?.let { return it }
                val fresh = elem!!.zero()
                set(fresh)
                return fresh
            }
            return (get() as? GoPtr<*>)?.value ?: elem?.zero?.invoke()
        }
        set(v) {
            set(if (structLike || v == null) v else com.xemantic.typescript.tsgo.runtime.GoBox(v))
        }
}

/** `reflect.MakeSlice(typ, len, cap)` of a slice type whose element zero value the type info knows. */
fun makeSlice(typ: Type?, len: Int, cap: Int): Value {
    val info = typ!!.info
    val elem = info.elem
    val ge = com.xemantic.typescript.tsgo.runtime.GoElem<Any?>({ elem?.zero?.invoke() })
    val s = GoSlice.make(ge, len, cap)
    return Value(info, { s })
}

/** `reflect.Append(s, x...)`. */
fun append(s: Value, vararg x: Value): Value {
    @Suppress("UNCHECKED_CAST") var out = s.`interface`() as GoSlice<Any?>
    for (v in x) out = out.append1(v.`interface`())
    val result = out
    return Value(s.type()!!.info, { result })
}

private fun raw(v: Any?): Any? = if (v is GoBasicValue) v.goRaw else v

/** Go's zero test for [v] of static type [t]. */
fun goIsZero(v: Any?, t: GoTypeInfo?): Boolean = when (v) {
    null -> true
    is GoSlice<*> -> v.isNil
    is GoMap<*, *> -> v.isNil
    is GoReflectStruct -> if (t != null && t.kind == GoTypeInfo.KIND_POINTER) false
        else v.goStructInfo().fields.indices.all { i -> goIsZero(v.goFieldPtr(i).value, v.goStructInfo().fields[i].type) }
    is Boolean -> !v
    is String -> v.isEmpty()
    is Int -> v == 0
    is Long -> v == 0L
    is UInt -> v == 0u
    is ULong -> v == 0uL
    is Double -> v.toRawBits() == 0L
    is GoBasicValue -> goIsZero(v.goRaw, null)
    else -> t?.let { v == it.zero() } ?: false
}

/** The dynamic type of [v] — an `any` holding a generated struct is taken as a POINTER to it (Go stores structs in interfaces by pointer throughout tsgo). */
fun typeInfoOf(v: Any?): GoTypeInfo = when (v) {
    null -> GoTypeInfo(0)
    is String -> GoTypeInfo(GoTypeInfo.KIND_STRING, "string")
    is Boolean -> GoTypeInfo(GoTypeInfo.KIND_BOOL, "bool")
    is Int -> GoTypeInfo(GoTypeInfo.KIND_INT, "int")
    is Long -> GoTypeInfo(GoTypeInfo.KIND_INT64, "int64")
    is UInt -> GoTypeInfo(GoTypeInfo.KIND_UINT32, "uint32")
    is ULong -> GoTypeInfo(GoTypeInfo.KIND_UINT64, "uint64")
    is Double -> GoTypeInfo(GoTypeInfo.KIND_FLOAT64, "float64")
    is GoBasicValue -> GoTypeInfo(typeInfoOf(v.goRaw).kind, v::class.simpleName ?: "", v::class)
    is GoSlice<*> -> GoTypeInfo(GoTypeInfo.KIND_SLICE)
    is GoMap<*, *> -> GoTypeInfo(GoTypeInfo.KIND_MAP)
    is GoReflectStruct -> GoTypeInfo.ptr(GoTypeInfo(GoTypeInfo.KIND_STRUCT, v.goStructInfo().name, v::class, structInfo = { v.goStructInfo() }))
    is Function<*> -> GoTypeInfo(GoTypeInfo.KIND_FUNC)
    else -> GoTypeInfo.ptr(GoTypeInfo(GoTypeInfo.KIND_STRUCT, v::class.simpleName ?: "", v::class))
}

/** `reflect.TypeOf(i)` (nil for a nil interface). */
fun typeOf(i: Any?): Type? = if (i == null) null else Type(typeInfoOf(i))

/** `reflect.TypeFor[T]()` for a STATIC T: the porter passes T's description. */
fun typeFor(info: GoTypeInfo): Type = Type(info)

/**
 * `reflect.TypeFor[T]()` for a type PARAMETER T: only its element kind is in scope, so the type is
 * read off T's zero value (enough for `Kind()`, which is all tsgo asks: `packagejson.Expected`).
 */
fun <T> typeForElem(elem: com.xemantic.typescript.tsgo.runtime.GoElem<T>): Type = Type(typeInfoOf(elem.zeroValue()).let { ti ->
    if (ti.kind == GoTypeInfo.KIND_POINTER && ti.elem?.kind == GoTypeInfo.KIND_STRUCT) ti.elem else ti
})

/** `reflect.ValueOf(i)`. */
fun valueOf(i: Any?): Value = if (i == null) Value() else Value(typeInfoOf(i), { i })

/** `reflect.DeepEqual(x, y)`. */
fun deepEqual(x: Any?, y: Any?): Boolean {
    if (x === y) return true
    if (x == null || y == null) return false
    return when {
        x is GoSlice<*> && y is GoSlice<*> -> x.isNil == y.isNil && x.len == y.len && (0 until x.len).all { deepEqual(x[it], y[it]) }
        x is GoMap<*, *> && y is GoMap<*, *> -> x.isNil == y.isNil && x.len == y.len && x.keysSnapshot().all { k ->
            @Suppress("UNCHECKED_CAST") val ym = y as GoMap<Any?, Any?>
            @Suppress("UNCHECKED_CAST") val xm = x as GoMap<Any?, Any?>
            ym.contains(k) && deepEqual(xm[k], ym[k])
        }
        // Go: pointers are deeply equal when they point to deeply equal values (`*int` options are `GoBox`es).
        x is GoPtr<*> && y is GoPtr<*> && x !is GoReflectStruct && y !is GoReflectStruct -> deepEqual(x.value, y.value)
        x is GoReflectStruct && y is GoReflectStruct -> x::class == y::class &&
            x.goStructInfo().fields.indices.all { deepEqual(x.goFieldPtr(it).value, y.goFieldPtr(it).value) }
        else -> x == y
    }
}

/**
 * `GoJsonStruct.goJsonFields()` of a generated struct (docs/goport-runtime.md § 9c): the members in
 * declaration order, named after their `json:` tag (else the Go name), `json:"-"` and unexported
 * fields dropped, an untagged embedded struct's members flattened in place.
 */
fun goJsonFieldsOf(s: GoReflectStruct): List<com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.JsonField> {
    val out = ArrayList<com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.JsonField>()
    val info = s.goStructInfo()
    info.fields.forEachIndexed { i, f ->
        val tag = StructTag(f.tag).lookup("json")
        val opts = if (tag.second) tag.first.split(',') else listOf("")
        if (opts[0] == "-" && opts.size == 1) return@forEachIndexed
        if (f.embedded && !tag.second) {
            val v = s.goFieldPtr(i).value
            if (v is GoReflectStruct) out += goJsonFieldsOf(v)
            return@forEachIndexed
        }
        if (!f.exported) return@forEachIndexed
        val type = f.type
        out += com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.JsonField(
            name = opts[0].ifEmpty { f.name },
            ptr = if (type.kind == GoTypeInfo.KIND_POINTER) JsonPointerField(s.goFieldPtr(i), type) else s.goFieldPtr(i),
            omitEmpty = "omitempty" in opts.drop(1),
            omitZero = "omitzero" in opts.drop(1),
            isZero = { v -> goIsZero(v, type) },
        )
    }
    return out
}

/**
 * A POINTER field as json sees it. Go's decoder allocates the pointee of a nil `*T`; the json shim reads
 * a target's type off its current value, so a nil pointer would decode as `any` (a `Double`, a `GoMap`).
 * This adapter converts what it is given into the pointee [type] describes: a basic value is boxed
 * (`*int` → `GoBox`), a JSON object/array becomes a fresh pointee that decodes itself
 * (`*collections.OrderedMap` → `UnmarshalJSONFrom`).
 */
private class JsonPointerField(private val ptr: GoPtr<Any?>, private val type: GoTypeInfo) : GoPtr<Any?> {
    override var value: Any?
        get() = ptr.value
        set(v) {
            ptr.value = convert(v)
        }

    private fun convert(v: Any?): Any? {
        if (v == null) return null
        val elem = type.elem ?: return v
        if (elem.kind == GoTypeInfo.KIND_STRUCT) {
            if (elem.cls != null && elem.cls.isInstance(v)) return v
            val fresh = elem.zero() ?: return v
            val json = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.marshal(v)
            if (json.second != null) return v
            val err = com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.unmarshal(json.first, fresh)
            return if (err == null) fresh else v
        }
        if (v is GoPtr<*>) return v
        val raw = when (elem.kind) {
            GoTypeInfo.KIND_INT, 3, 4, 5 -> (v as? Double)?.toInt() ?: v
            GoTypeInfo.KIND_INT64 -> (v as? Double)?.toLong() ?: v
            GoTypeInfo.KIND_UINT32 -> (v as? Double)?.toUInt() ?: v
            GoTypeInfo.KIND_UINT64, 7 -> (v as? Double)?.toULong() ?: v
            else -> v
        }
        // A named basic pointee (a value class) wraps the raw value.
        val wrapped = (elem.zero() as? GoBasicValue)?.goWithRaw(raw) ?: raw
        return com.xemantic.typescript.tsgo.runtime.GoBox(wrapped)
    }

    override fun equals(other: Any?): Boolean = other is JsonPointerField && other.ptr == ptr
    override fun hashCode(): Int = ptr.hashCode()
}
