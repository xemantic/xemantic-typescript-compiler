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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * The DRIFT PIN for the runtime's common surface.
 *
 * [KirRuntimeApi] states, by hand, what `JsObject` and `JsArray` offer, because
 * neither of the mechanical routes works: Java reflection cannot see
 * nullability, and `kotlin-reflect` on this module's classpath is older than
 * the metadata the runtime is compiled with. A second copy of a public API
 * drifts — so this reflects over the REAL classes and fails the moment a
 * declared member is absent or its JVM signature disagrees.
 *
 * The direction that matters is "declared here, absent there": that one types a
 * consumer's call against a method nothing implements, and the consumer's own
 * compiler cannot see it, because the consumer compiles against the metadata.
 * The reverse — a runtime member this surface does not declare — is deliberate
 * and is not a failure.
 */
class KirRuntimeApiTest {

    @Test
    fun `every declared runtime member exists on the real class`() {
        val problems = mutableListOf<String>()
        KirRuntimeApi.module.declarations.filterIsInstance<KotlinClass>().forEach { declared ->
            val real = runCatching {
                Class.forName("${KirRuntimeApi.PACKAGE}.${declared.name}")
            }.getOrNull()
            if (real == null) {
                problems += "no runtime class ${KirRuntimeApi.PACKAGE}.${declared.name}"
                return@forEach
            }
            if (declared.constructorParameters?.isEmpty() == true &&
                real.constructors.none { it.parameterCount == 0 }
            ) {
                problems += "${declared.name} has no public no-argument constructor"
            }
            declared.members.forEach { member ->
                when (member) {
                    is KotlinFunction -> problems += checkFunction(real, member)
                    is KotlinProperty -> problems += checkProperty(real, member)
                    else -> problems += "${declared.name}.${member.name} is not expressible"
                }
            }
        }
        assert(problems.isEmpty())
    }

    /** A NEGATIVE CONTROL: the check above can fail. */
    @Test
    fun `a member the runtime does not have is reported`() {
        val real = Class.forName("${KirRuntimeApi.PACKAGE}.JsObject")
        val absent = KotlinFunction(
            "thereIsNoSuchMethod",
            emptyList(),
            KotlinType.ANY,
            "test",
        )
        assert(checkFunction(real, absent).isNotEmpty())
    }

    /** …and it can fail on the SIGNATURE, not only on the name. */
    @Test
    fun `a member whose parameter types disagree is reported`() {
        val real = Class.forName("${KirRuntimeApi.PACKAGE}.JsObject")
        val wrong = KotlinFunction(
            "get",
            listOf(KotlinParameter("name", KotlinType.DOUBLE)),
            KotlinType.ANY,
            "test",
        )
        assert(checkFunction(real, wrong).isNotEmpty())
    }

    private fun checkFunction(real: Class<*>, function: KotlinFunction): List<String> {
        val expected = function.parameters.map { jvmClassOf(it.type) }
        if (expected.any { it == null }) {
            return listOf("${real.simpleName}.${function.name} has a parameter this pin cannot map")
        }
        val method = real.methods.firstOrNull {
            it.name == function.name &&
                it.parameterTypes.toList() == expected.map { parameter -> parameter!! }
        } ?: return listOf(
            "${real.simpleName}.${function.name}(" +
                function.parameters.joinToString { it.type.render() } + ") is not on the runtime"
        )
        val returnType = jvmClassOf(function.returnType)
        return if (returnType != null && method.returnType != returnType) {
            listOf(
                "${real.simpleName}.${function.name} returns ${method.returnType.simpleName}, " +
                    "declared ${function.returnType.render()}"
            )
        } else {
            emptyList()
        }
    }

    private fun checkProperty(real: Class<*>, property: KotlinProperty): List<String> {
        val capitalized = property.name.replaceFirstChar { it.uppercase() }
        val problems = mutableListOf<String>()
        val getter = real.methods.firstOrNull {
            it.name == "get$capitalized" && it.parameterCount == 0
        }
        if (getter == null) {
            problems += "${real.simpleName}.${property.name} has no getter on the runtime"
        } else {
            jvmClassOf(property.type)?.let {
                if (getter.returnType != it) {
                    problems += "${real.simpleName}.${property.name} is ${getter.returnType.simpleName}"
                }
            }
        }
        if (property.mutable && real.methods.none {
                it.name == "set$capitalized" && it.parameterCount == 1
            }
        ) {
            problems += "${real.simpleName}.${property.name} is declared mutable and has no setter"
        }
        return problems
    }

    /**
     * The JVM class a declared type has, or null where the pin does not judge.
     *
     * A nullable type answers null deliberately: `Any?` and `Any` are one JVM
     * class, so the pin has nothing to check there — nullability is exactly what
     * reflection cannot see, and pretending otherwise is what would make this a
     * pin that passes for the wrong reason.
     */
    private fun jvmClassOf(type: KotlinType): Class<*>? = when (type.render()) {
        "kotlin.Double" -> java.lang.Double.TYPE
        "kotlin.Boolean" -> java.lang.Boolean.TYPE
        "kotlin.String" -> String::class.java
        "kotlin.Unit" -> java.lang.Void.TYPE
        "kotlin.Any?" -> Any::class.java
        "(Any?) -> Any?" -> Class.forName("kotlin.jvm.functions.Function1")
        "${KirRuntimeApi.PACKAGE}.JsArray" -> Class.forName("${KirRuntimeApi.PACKAGE}.JsArray")
        "${KirRuntimeApi.PACKAGE}.JsObject" -> Class.forName("${KirRuntimeApi.PACKAGE}.JsObject")
        else -> null
    }

}
