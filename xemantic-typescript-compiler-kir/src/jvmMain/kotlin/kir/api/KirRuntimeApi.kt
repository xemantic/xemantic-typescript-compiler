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

package com.xemantic.typescript.compiler.kir.api

import java.nio.file.Path

/**
 * The JS value types an exported API mentions, as a COMMON Kotlin surface.
 *
 * ## The problem this solves
 *
 * A TypeScript object type is a property bag at run time and an array is a
 * `JsArray`, and both of those are classes in `…kir.runtime` — **JVM** Kotlin,
 * compiled by this module's own build. A metadata klib is common code: it
 * cannot see a JVM class. So without this, every object and array position on
 * an exported API erases to `Any?`, and a TOML parser exports as "returns
 * something" rather than "returns something you can read".
 *
 * ## The shape of the answer
 *
 * A SECOND metadata klib, declaring those types under their real fully
 * qualified names, produced by the same machinery and put on the exported
 * library's compile classpath. That is exactly the pairing a Kotlin
 * Multiplatform library already is — common metadata for `commonMain`, a
 * platform artifact for the platform compilation — and naming the JVM classes'
 * own package is what makes the platform half possible later rather than
 * something to retrofit.
 *
 * ## Why the declarations are written here rather than derived
 *
 * A second copy of a public API drifts, which CLAUDE.md says at length. Two
 * mechanical alternatives were available and both are worse: Java reflection
 * loses nullability, which is the one thing this surface must state; and
 * `kotlin.reflect` would read metadata written by a NEWER compiler than the
 * `kotlin-reflect` on this module's classpath.
 *
 * So the surface is stated, and the drift is caught by a pin instead:
 * `KirRuntimeApiTest` reflects over the REAL classes and fails when a declared
 * member is absent or its JVM signature disagrees. A declaration that does not
 * exist on the runtime is the failure that matters — it would type a consumer's
 * call against a method nothing implements.
 *
 * The surface is deliberately a SUBSET: `JsObject` and `JsArray`, which is
 * where the whole prize is. `JsMap`, `JsDate`, `JsSet`, `JsRegExp` and `JsError`
 * are still `Any?` on an exported API, because a library type must be mapped BY
 * NAME (as `ErasedTypes` does) rather than caught by the object fallback, and
 * that table is its own decision.
 */
internal object KirRuntimeApi {

    const val PACKAGE: String = "com.xemantic.typescript.compiler.kir.runtime"

    val jsObject: KotlinType = KotlinType.Named("$PACKAGE.JsObject")

    val jsArray: KotlinType = KotlinType.Named("$PACKAGE.JsArray")

    private val jsMap: KotlinType = KotlinType.Named("$PACKAGE.JsMap")

    private val jsSet: KotlinType = KotlinType.Named("$PACKAGE.JsSet")

    private val jsDate: KotlinType = KotlinType.Named("$PACKAGE.JsDate")

    private val jsRegExp: KotlinType = KotlinType.Named("$PACKAGE.JsRegExp")

    private val jsError: KotlinType = KotlinType.Named("$PACKAGE.JsError")

    /**
     * The runtime type a LIBRARY type of this name erases to, if any.
     *
     * `KirIntrinsics.libraryClass`'s table, mirrored — and it must stay
     * mirrored, because the two answer the same question for the two halves of
     * one artifact pair: this one types the declaration a Kotlin consumer
     * compiles against, that one types the value the compiled program actually
     * holds. `RegExpMatchArray` is an ARRAY there and here, which is the entry
     * a reader is most likely to get wrong.
     */
    fun libraryType(name: String): KotlinType? = when (name) {
        "Array", "ReadonlyArray", "RegExpMatchArray", "RegExpExecArray" -> jsArray
        "Map", "ReadonlyMap", "WeakMap" -> jsMap
        "Set", "ReadonlySet", "WeakSet" -> jsSet
        "RegExp" -> jsRegExp
        "Date" -> jsDate
        "Error" -> jsError
        else -> null
    }

    private const val ORIGIN = "kir/runtime/JsRuntime.kt"

    private val any = KotlinType.ANY
    private val double = KotlinType.DOUBLE
    private val string = KotlinType.STRING
    private val boolean = KotlinType.BOOLEAN
    private val unit = KotlinType.UNIT
    private val callback = KotlinType.Function(1)

    /** The declarations, as the same model an exported TypeScript API uses. */
    val module: KotlinApiModule = KotlinApiModule(
        PACKAGE,
        listOf(
            KotlinClass(
                name = "JsObject",
                constructorParameters = emptyList(),
                members = listOf(
                    function("get", listOf("name" to string), any),
                    function("set", listOf("name" to string, "value" to any), unit),
                    function("has", listOf("name" to string), boolean),
                    function("delete", listOf("name" to string), boolean),
                    function("keys", emptyList(), jsArray),
                ),
                origin = ORIGIN,
            ),
            KotlinClass(
                name = "JsArray",
                constructorParameters = emptyList(),
                members = listOf(
                    KotlinProperty("length", double, mutable = true, origin = ORIGIN),
                    // `get`/`set` are INDEXING operators, so common code writes
                    // `a[0.0]` — the spelling a JavaScript array has.
                    function("get", listOf("index" to double), any, isOperator = true),
                    function("set", listOf("index" to double, "value" to any), unit, isOperator = true),
                    function("push", listOf("value" to any), double),
                    function("pop", emptyList(), any),
                    function("shift", emptyList(), any),
                    function("unshift", listOf("value" to any), double),
                    function("indexOf", listOf("value" to any), double),
                    function("includes", listOf("value" to any), boolean),
                    function("slice", emptyList(), jsArray),
                    function("concat", listOf("other" to jsArray), jsArray),
                    function("join", listOf("separator" to string), string),
                    // The callback is `Any?` rather than a one-parameter
                    // function type: JavaScript passes `(element, index, array)`
                    // to every one of these, and a `Function1` slot would drop
                    // the index silently. The runtime calls through `jsCall`,
                    // which adapts whatever arity the callback declares.
                    function("forEach", listOf("callback" to any), unit),
                    function("map", listOf("callback" to any), jsArray),
                    function("filter", listOf("callback" to any), jsArray),
                    function("find", listOf("callback" to any), any),
                    function("findIndex", listOf("callback" to any), double),
                    function("some", listOf("callback" to any), boolean),
                    function("every", listOf("callback" to any), boolean),
                    function("at", listOf("index" to double), any),
                    function("reverse", emptyList(), jsArray),
                    function("sort", emptyList(), jsArray),
                    function("sort", listOf("comparator" to any), jsArray),
                ),
                origin = ORIGIN,
            ),
            KotlinClass(
                name = "JsMap",
                constructorParameters = emptyList(),
                members = listOf(
                    KotlinProperty("size", double, mutable = false, origin = ORIGIN),
                    function("get", listOf("key" to any), any),
                    function("set", listOf("key" to any, "value" to any), jsMap),
                    function("has", listOf("key" to any), boolean),
                    function("delete", listOf("key" to any), boolean),
                    function("clear", emptyList(), unit),
                    function("keys", emptyList(), jsArray),
                    function("values", emptyList(), jsArray),
                ),
                origin = ORIGIN,
            ),
            KotlinClass(
                name = "JsSet",
                constructorParameters = emptyList(),
                members = listOf(
                    KotlinProperty("size", double, mutable = false, origin = ORIGIN),
                    function("add", listOf("value" to any), jsSet),
                    function("has", listOf("value" to any), boolean),
                    function("delete", listOf("value" to any), boolean),
                    function("clear", emptyList(), unit),
                    function("values", emptyList(), jsArray),
                ),
                origin = ORIGIN,
            ),
            KotlinClass(
                name = "JsDate",
                constructorParameters = listOf(KotlinParameter("value", any)),
                members = listOf(
                    function("getTime", emptyList(), double),
                    function("valueOf", emptyList(), double),
                    function("toISOString", emptyList(), string),
                ),
                origin = ORIGIN,
            ),
            KotlinClass(
                name = "JsRegExp",
                constructorParameters = listOf(
                    KotlinParameter("source", string),
                    KotlinParameter("flags", string),
                ),
                members = listOf(
                    KotlinProperty("global", boolean, mutable = false, origin = ORIGIN),
                    function("test", listOf("input" to string), boolean),
                    // A `null` result is how JavaScript says "no match", and it
                    // is the one place on this surface where the nullability is
                    // the whole meaning.
                    function("exec", listOf("input" to string), jsArray.asNullable()),
                ),
                origin = ORIGIN,
            ),
            KotlinClass(
                name = "JsError",
                constructorParameters = listOf(KotlinParameter("message", any)),
                members = listOf(
                    KotlinProperty("name", string, mutable = true, origin = ORIGIN),
                ),
                origin = ORIGIN,
            ),
        ),
    )

    private fun function(
        name: String,
        parameters: List<Pair<String, KotlinType>>,
        returnType: KotlinType,
        isOperator: Boolean = false,
    ) = KotlinFunction(
        name,
        parameters.map { (parameterName, type) -> KotlinParameter(parameterName, type) },
        returnType,
        ORIGIN,
        isOperator,
    )

}

/**
 * Writes the runtime's common surface as a metadata klib at [output].
 *
 * An exported library klib is compiled AGAINST this one and a consumer needs
 * both on its classpath, which is what any library dependency is.
 */
internal fun writeRuntimeMetadataKlib(output: Path): MetadataKlibResult =
    compileMetadataKlib(KirRuntimeApi.module.render(), output, "xtsc-kir-runtime")
