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

package com.xemantic.typescript.compiler.externals

import com.xemantic.typescript.compiler.CheckedLens
import com.xemantic.typescript.compiler.Type
import com.xemantic.typescript.compiler.TypeFlags

/**
 * One rendered top-level declaration — a generated interface, or the loud
 * record of one that could not be generated yet.
 *
 * The model carries STRINGS only: types were resolved and mapped while the
 * checker's lens was still valid, so rendering is a pure text fold with no
 * checker dependency.
 */
internal sealed interface ExternalDeclaration

internal class ExternalInterface(
    val name: String,
    /** The declaration's own type-parameter NAMES, rendered as written. */
    val typeParameters: List<String>,
    /**
     * (EXT.2) Loud per-declaration records — a constrained or defaulted type
     * parameter whose constraint Kotlin externals cannot carry — rendered as
     * comments directly under the header, never dropped.
     */
    val headerMarkers: List<String>,
    val members: List<ExternalMember>,
) : ExternalDeclaration

/**
 * (EXT.2) An exported, non-generic type alias whose body mapped — rendered as
 * `public typealias`. USES of the alias still render the RESOLVED type (the
 * (EXT.1) `Species` -> `String` pin): the alias declaration is emitted for the
 * consumer's vocabulary, the resolution is what the checker knows.
 */
internal class ExternalTypeAlias(
    val name: String,
    /** Full Kotlin type text of the alias body. */
    val body: String,
) : ExternalDeclaration

/**
 * (EXT.3) An exported top-level function — `public external fun`. In the
 * compile-gate variant a non-external function must have a BODY, and the gate
 * has no classpath beyond Kotlin's built-ins, so the variant renders
 * `= null!!` — an expression of type `Nothing`, legal as the body of any
 * return type, built from nothing but the language.
 */
internal class ExternalTopLevelFunction(
    val name: String,
    val typeParameters: List<String>,
    /** Loud records — a constraint or default not carried — above the fun. */
    val markers: List<String>,
    val parameters: List<ExternalParameter>,
    val returnType: String,
) : ExternalDeclaration

/**
 * (EXT.4) An exported class — `public external class`, the one declared
 * constructor as the primary constructor, TS `static` members as the companion
 * object. In the compile-gate variant every member grows a `= null!!` body
 * (class members, unlike interface members, cannot stay abstract without the
 * modifier), decided by the renderer flag, never by text surgery.
 */
internal class ExternalClass(
    val name: String,
    val typeParameters: List<String>,
    /** Loud records — a constraint or default not carried — under the header. */
    val headerMarkers: List<String>,
    val isAbstract: Boolean,
    /** null = no declared constructor (Kotlin's implicit default matches TS's). */
    val constructorParameters: List<ExternalParameter>?,
    val members: List<ExternalMember>,
    /** TS `static` members — rendered as the companion object. */
    val staticMembers: List<ExternalMember>,
) : ExternalDeclaration

/**
 * (EXT.4) An exported (non-const) enum — Kotlin has no `external enum class`,
 * so the shape is a sealed interface whose companion object carries one `val`
 * per entry, typed by the interface: exactly what the runtime enum object
 * exposes. The gate variant initializes each val with `= null!!`.
 */
internal class ExternalEnum(
    val name: String,
    /** Entry NAMES as written — each becomes a companion `val`. */
    val entries: List<String>,
    /** Loud records for entries that could not be rendered. */
    val markers: List<String>,
) : ExternalDeclaration

/** A declaration (EXT.1) refuses — rendered as a marker, never dropped. */
internal class SkippedDeclaration(val description: String) : ExternalDeclaration

internal sealed interface ExternalMember

internal class ExternalProperty(
    val name: String,
    /** Full Kotlin type text, `?` and fallback marker included. */
    val type: String,
    val readOnly: Boolean,
) : ExternalMember

internal class ExternalFunction(
    val name: String,
    val parameters: List<ExternalParameter>,
    val returnType: String,
) : ExternalMember

internal class ExternalParameter(
    val name: String,
    val type: String,
)

/** A member (EXT.1) refuses — rendered as a marker, never dropped. */
internal class SkippedMember(val description: String) : ExternalMember

/**
 * The ONE place the TypeScript-to-Kotlin type mapping is decided.
 *
 * The MVP table, applied to the CHECKER-RESOLVED type (never to annotation
 * syntax — an alias has already been resolved away by the time a type gets
 * here):
 *
 *  - `string`  -> `String`
 *  - `number`  -> `Double`
 *  - `boolean` -> `Boolean`
 *  - `void`    -> `Unit`, in RETURN position only — a property typed `void`
 *    is not a `Unit` slot, it is an oddity that falls through to the marker
 *  - optional member or parameter `p?: T` -> the mapped type made nullable
 *
 * ANY other type — unions, `any`, `unknown`, literals, object types, generics,
 * references to other interfaces — maps to the ONE documented fallback:
 * `Any?` followed by a marker comment carrying the checker's own rendering of
 * the type, so nothing is ever dropped silently. The fallback is already
 * nullable, which is why optionality does not add a second `?` to it. Note
 * `errorType` (a degraded resolution) is an intrinsic named `error` carrying
 * the `any` flag, so it lands in the fallback too — marked, never mapped.
 */
internal fun kotlinTypeText(
    type: Type,
    optional: Boolean,
    returnPosition: Boolean,
    lens: CheckedLens,
    scope: TypeScope = TypeScope.EMPTY,
): String {
    val mapped = mappedText(type, returnPosition, scope)
    return when {
        mapped == null ->
            "Any? /* xtsc: unmapped ${commentSafe(lens.render(type))} */"
        optional -> "$mapped?"
        else -> mapped
    }
}

/**
 * (EXT.2) What a type may resolve AGAINST at one use site: the enclosing
 * declaration's own type-parameter names, and the predicate deciding whether a
 * named type is one THIS generation emits.
 *
 * [generatedNameOf] must demand POSITIVE evidence (the `kir/api` mapper's
 * lesson): a type is rendered by NAME only when its symbol's declaration IS one
 * of the exported declarations being generated — a lib type or a non-exported
 * neighbour that merely shares a name falls to the fallback, never to a bare
 * name the generated module does not declare.
 */
internal class TypeScope(
    val ownTypeParams: Set<String>,
    val generatedNameOf: (Type) -> String?,
) {
    internal companion object {
        val EMPTY: TypeScope = TypeScope(emptySet()) { null }
    }
}

/**
 * (EXT.2) The mapping WITHOUT the fallback: null where [kotlinTypeText] would
 * mark — for callers that must REFUSE a declaration rather than degrade a
 * member (the typealias body).
 */
internal fun kotlinTypeTextOrNull(
    type: Type,
    returnPosition: Boolean,
    scope: TypeScope,
): String? = mappedText(type, returnPosition, scope)

private fun mappedText(type: Type, returnPosition: Boolean, scope: TypeScope): String? {
    mappedIntrinsic(type, returnPosition)?.let { return it }
    // The enclosing declaration's own type parameter, by its written name.
    if (type is Type.TypeParam) {
        val name = type.symbol?.name ?: return null
        return if (name in scope.ownTypeParams) kotlinIdentifier(name) else null
    }
    // A reference to an interface THIS generation emits — a bare use...
    scope.generatedNameOf(type)?.let { name ->
        if (type !is Type.Reference) return kotlinIdentifier(name)
        // ...or a generic instantiation, rendered only when EVERY argument
        // maps: one unmappable argument falls the whole reference back, so a
        // half-translated `Box<...>` never appears.
        val args = type.resolvedTypeArguments ?: return kotlinIdentifier(name)
        val mappedArgs = args.map { argument ->
            mappedText(argument, returnPosition = false, scope) ?: return null
        }
        return if (mappedArgs.isEmpty()) kotlinIdentifier(name)
        else "${kotlinIdentifier(name)}<${mappedArgs.joinToString(", ")}>"
    }
    return null
}

private fun mappedIntrinsic(type: Type, returnPosition: Boolean): String? {
    if (type !is Type.Intrinsic) return null
    return when {
        TypeFlags.String in type.flags -> "String"
        TypeFlags.Number in type.flags -> "Double"
        TypeFlags.Boolean in type.flags -> "Boolean"
        TypeFlags.Void in type.flags && returnPosition -> "Unit"
        else -> null
    }
}

/**
 * Makes the checker's rendering safe inside a single-line block comment: a
 * nested comment-close would end the marker early and break the generated
 * file, and a line break would break the single-line member.
 */
internal fun commentSafe(rendered: String): String = rendered
    .replace("*/", "* /")
    .replace('\n', ' ')
    .replace('\r', ' ')

/**
 * Renders the collected declarations as Kotlin source.
 *
 * [external] selects the real output (`public external interface`) or the
 * compile-gate variant (`public interface`) — a renderer flag, never a text
 * strip, so the two renderings cannot drift apart. See
 * [KotlinExternals.compileCheckSource] for why the variant exists.
 */
internal fun renderKotlinExternals(
    declarations: List<ExternalDeclaration>,
    external: Boolean,
): String = buildString {
    declarations.forEachIndexed { index, declaration ->
        if (index > 0) appendLine()
        when (declaration) {
            is SkippedDeclaration ->
                appendLine("/* xtsc: skipped ${declaration.description} */")
            is ExternalInterface -> {
                val keyword = if (external) "external interface" else "interface"
                val typeParams =
                    if (declaration.typeParameters.isEmpty()) ""
                    else declaration.typeParameters
                        .joinToString(", ", prefix = "<", postfix = ">") {
                            kotlinIdentifier(it)
                        }
                appendLine(
                    "public $keyword ${kotlinIdentifier(declaration.name)}$typeParams {"
                )
                for (marker in declaration.headerMarkers) {
                    appendLine("    /* xtsc: $marker */")
                }
                for (member in declaration.members) appendMember(member)
                appendLine("}")
            }
            is ExternalTypeAlias ->
                appendLine(
                    "public typealias ${kotlinIdentifier(declaration.name)} = ${declaration.body}"
                )
            is ExternalTopLevelFunction -> {
                for (marker in declaration.markers) appendLine("/* xtsc: $marker */")
                val typeParams =
                    if (declaration.typeParameters.isEmpty()) ""
                    else declaration.typeParameters
                        .joinToString(", ", prefix = "<", postfix = "> ") {
                            kotlinIdentifier(it)
                        }
                val parameters = declaration.parameters.joinToString(", ") {
                    "${kotlinIdentifier(it.name)}: ${it.type}"
                }
                val keyword = if (external) "external fun" else "fun"
                val body = if (external) "" else " = null!!"
                appendLine(
                    "public $keyword $typeParams${kotlinIdentifier(declaration.name)}" +
                        "($parameters): ${declaration.returnType}$body"
                )
            }
            is ExternalClass -> {
                // Kotlin's canonical modifier order: visibility, then
                // abstract, then external.
                val abstractModifier = if (declaration.isAbstract) "abstract " else ""
                val externalModifier = if (external) "external " else ""
                val typeParams =
                    if (declaration.typeParameters.isEmpty()) ""
                    else declaration.typeParameters
                        .joinToString(", ", prefix = "<", postfix = ">") {
                            kotlinIdentifier(it)
                        }
                val constructorText = declaration.constructorParameters
                    ?.joinToString(", ", prefix = "(", postfix = ")") {
                        "${kotlinIdentifier(it.name)}: ${it.type}"
                    } ?: ""
                val header = "public $abstractModifier${externalModifier}class " +
                    "${kotlinIdentifier(declaration.name)}$typeParams$constructorText"
                val hasBody = declaration.headerMarkers.isNotEmpty() ||
                    declaration.members.isNotEmpty() ||
                    declaration.staticMembers.isNotEmpty()
                if (!hasBody) {
                    appendLine(header)
                } else {
                    appendLine("$header {")
                    for (marker in declaration.headerMarkers) {
                        appendLine("    /* xtsc: $marker */")
                    }
                    for (member in declaration.members) {
                        appendMember(member, needsBody = !external)
                    }
                    if (declaration.staticMembers.isNotEmpty()) {
                        appendLine("    public companion object {")
                        for (member in declaration.staticMembers) {
                            appendMember(member, needsBody = !external, indent = "        ")
                        }
                        appendLine("    }")
                    }
                    appendLine("}")
                }
            }
            is ExternalEnum -> {
                val keyword =
                    if (external) "sealed external interface" else "sealed interface"
                val header = "public $keyword ${kotlinIdentifier(declaration.name)}"
                if (declaration.entries.isEmpty() && declaration.markers.isEmpty()) {
                    appendLine(header)
                } else {
                    appendLine("$header {")
                    for (marker in declaration.markers) {
                        appendLine("    /* xtsc: $marker */")
                    }
                    if (declaration.entries.isNotEmpty()) {
                        appendLine("    public companion object {")
                        val body = if (external) "" else " = null!!"
                        for (entry in declaration.entries) {
                            appendLine(
                                "        public val ${kotlinIdentifier(entry)}: " +
                                    "${kotlinIdentifier(declaration.name)}$body"
                            )
                        }
                        appendLine("    }")
                    }
                    appendLine("}")
                }
            }
        }
    }
}

/**
 * [needsBody] is the gate variant's CLASS-member rule: a non-external class
 * member cannot stay bodiless (an interface member can), so a fun grows
 * `= null!!` and a property the same as its initializer — `Nothing`-typed,
 * legal for any member type, built from language built-ins alone.
 */
private fun StringBuilder.appendMember(
    member: ExternalMember,
    needsBody: Boolean = false,
    indent: String = "    ",
) {
    val body = if (needsBody) " = null!!" else ""
    when (member) {
        is ExternalProperty -> {
            val keyword = if (member.readOnly) "val" else "var"
            appendLine(
                "${indent}public $keyword ${kotlinIdentifier(member.name)}: ${member.type}$body"
            )
        }
        is ExternalFunction -> {
            val parameters = member.parameters.joinToString(", ") {
                "${kotlinIdentifier(it.name)}: ${it.type}"
            }
            appendLine(
                "${indent}public fun ${kotlinIdentifier(member.name)}($parameters): ${member.returnType}$body"
            )
        }
        is SkippedMember ->
            appendLine("${indent}/* xtsc: skipped ${member.description} */")
    }
}

/**
 * Kotlin's HARD keywords — the names that cannot appear as bare identifiers.
 * Soft keywords (`by`, `get`, `field`, ...) are legal identifiers and need no
 * escape.
 */
private val kotlinHardKeywords = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
    "if", "in", "interface", "is", "null", "object", "package", "return",
    "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
    "var", "when", "while",
)

/**
 * A TypeScript identifier rendered as a Kotlin one: backticked when it
 * collides with a hard keyword or uses a character Kotlin identifiers do not
 * allow (`$` is the common case — legal in TypeScript, backtick-only in
 * Kotlin).
 */
internal fun kotlinIdentifier(name: String): String {
    val plain = name.isNotEmpty() &&
        name !in kotlinHardKeywords &&
        (name[0].isLetter() || name[0] == '_') &&
        name.all { it.isLetterOrDigit() || it == '_' }
    return if (plain) name else "`$name`"
}
