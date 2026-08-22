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

/**
 * Renders a [KotlinApiModule] as Kotlin source.
 *
 * The metadata is produced by compiling this text with kotlinc's own metadata
 * compiler rather than by writing serialized metadata directly, and that is a
 * decision worth defending: Kotlin's metadata is a versioned protobuf whose
 * writer lives in the compiler, so hand-writing it would be a second
 * implementation of a format that moves every release — the exact shape of
 * defect this project's own CLAUDE.md keeps recording. Going through the
 * compiler makes the artifact BY CONSTRUCTION what kotlinc would have written,
 * and leaves a readable intermediate a human can review.
 *
 * ## Why the bodies look like that
 *
 * Every function and property gets `null as T`, and the reason is that the
 * metadata compilation must type-check with NOTHING on its classpath — not even
 * the standard library, whose common metadata is a separate artifact this
 * project does not ship. `TODO()` and `error(…)` are stdlib; a cast of `null`
 * is a language construct, legal for every type including the primitives, and
 * a `Unit` function needs no body at all.
 *
 * The bodies are not in the artifact: a metadata klib carries declarations, and
 * only an `inline` function — which nothing here generates — would carry a body
 * with it.
 */
internal fun KotlinApiModule.render(): String = buildString {
    appendLine("// Generated from TypeScript by xemantic-typescript-compiler.")
    appendLine("// The public API of a TypeScript module, as Kotlin declarations.")
    appendLine("//")
    appendLine("// Do not edit: every declaration below is derived from a checked")
    appendLine("// TypeScript program, and the comment above each one says from where.")
    appendLine()
    appendLine("package $packageName")
    declarations.forEach { declaration ->
        appendLine()
        renderDeclaration(declaration, indent = "")
    }
}

private fun StringBuilder.renderDeclaration(declaration: KotlinDeclaration, indent: String) {
    appendLine("$indent// ${declaration.origin}")
    when (declaration) {
        is KotlinFunction -> {
            val parameters = declaration.parameters.joinToString(", ") {
                "${it.name}: ${it.type.render()}"
            }
            val returns = declaration.returnType.render()
            val modifiers = if (declaration.isOperator) "public operator fun" else "public fun"
            if (returns == "kotlin.Unit") {
                appendLine("$indent$modifiers ${declaration.name}($parameters) {}")
            } else {
                appendLine(
                    "$indent$modifiers ${declaration.name}($parameters): $returns = " +
                        initializerFor(declaration.returnType)
                )
            }
        }
        is KotlinProperty -> {
            val type = declaration.type.render()
            val keyword = if (declaration.mutable) "var" else "val"
            appendLine("${indent}public $keyword ${declaration.name}: $type")
            appendLine("$indent    get() = ${initializerFor(declaration.type)}")
            if (declaration.mutable) appendLine("$indent    set(value) {}")
        }
        is KotlinClass -> {
            val parameters = declaration.constructorParameters
            val header = when {
                parameters == null -> "${indent}public class ${declaration.name} private constructor()"
                else -> "${indent}public class ${declaration.name}(" +
                    parameters.joinToString(", ") { "${it.name}: ${it.type.render()}" } + ")"
            }
            if (declaration.members.isEmpty()) {
                appendLine(header)
            } else {
                appendLine("$header {")
                declaration.members.forEach { member ->
                    appendLine()
                    renderDeclaration(member, "$indent    ")
                }
                appendLine("$indent}")
            }
        }
        is KotlinConstantObject -> {
            if (declaration.members.isEmpty()) {
                appendLine("${indent}public object ${declaration.name}")
            } else {
                appendLine("${indent}public object ${declaration.name} {")
                declaration.members.forEach { member ->
                    appendLine()
                    renderDeclaration(member, "$indent    ")
                }
                appendLine("$indent}")
            }
        }
    }
}

/**
 * An expression of the given type that needs no library to type-check.
 *
 * `null as T` is legal for every type Kotlin has, including the primitives; it
 * would throw at run time and never runs, because a metadata klib holds no
 * bodies. A nullable type takes a plain `null`, which is the same value without
 * the compiler's "this cast can never succeed" warning.
 */
private fun initializerFor(type: KotlinType): String =
    if (type.nullable) "null" else "null as ${type.render()}"
