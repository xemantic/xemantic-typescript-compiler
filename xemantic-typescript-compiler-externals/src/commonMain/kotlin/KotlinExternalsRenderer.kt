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
    /**
     * (EXT.8) Supertypes that are GENERATED interfaces, as Kotlin type text
     * (`Base`, `Box<String>`); every other base is a marker among [members].
     */
    val supertypes: List<String>,
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
    /** (EXT.5) The alias's own type-parameter NAMES; empty for a plain alias. */
    val typeParameters: List<String>,
    /** (EXT.5) Loud records — a constraint or default not carried. */
    val markers: List<String>,
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
    /** (EXT.8) The `extends` base when it is a GENERATED class, as Kotlin type text. */
    val superClass: String?,
    /** (EXT.8) The `implements` bases that are GENERATED interfaces. */
    val interfaces: List<String>,
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

/**
 * (EXT.9) An exported top-level value — `public external val|var x: T`. The
 * gate variant initializes it with `= null!!` (a top-level property needs an
 * initializer or a getter).
 */
internal class ExternalTopLevelValue(
    val name: String,
    /** Full Kotlin type text, fallback marker included. */
    val type: String,
    val readOnly: Boolean,
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
    /** (EXT.5) A generic METHOD's own type-parameter names, syntactic. */
    val typeParameters: List<String>,
    /** (EXT.5) Loud per-member records — constraints/defaults not carried. */
    val markers: List<String>,
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
 * (EXT.5) The Kotlin-signature key of a function: name, type-parameter names and
 * the parameter types with their `/* xtsc: … */` markers STRIPPED — two literal
 * types both falling to `Any?` conflict however different their markers read.
 * Shared by the overload collapse (collector) and the override decision
 * (renderer), so the two cannot disagree about what "the same signature" is.
 */
internal fun overloadSignature(
    name: String,
    typeParameters: List<String>,
    parameters: List<ExternalParameter>,
): String = buildString {
    append(name)
    append('<').append(typeParameters.joinToString(","))
    append(">(")
    parameters.joinTo(this, ",") { it.type.substringBefore(" /* xtsc:") }
    append(')')
}

/**
 * (EXT.8) What a declaration INHERITS from its generated bases, transitively —
 * the input to Kotlin's `override` rule, which TypeScript has no counterpart
 * for: a subinterface may simply redeclare a base member, Kotlin must say
 * `override`, and a class member overridden below must be `open`.
 *
 * Bases are looked up by NAME (the supertype text with any `<…>` stripped) over
 * the generation's own declarations — a supertype text is only ever produced
 * for a generated target, so the lookup cannot miss for a reason other than a
 * cross-file name collision, which [ExternalsCollector.finish] already reduced
 * to one declaration per name.
 */
private class Inheritance(declarations: List<ExternalDeclaration>) {

    private val byName = HashMap<String, ExternalDeclaration>()

    init {
        for (declaration in declarations) {
            when (declaration) {
                is ExternalInterface -> byName.putIfAbsent(declaration.name, declaration)
                is ExternalClass -> byName.putIfAbsent(declaration.name, declaration)
                else -> {}
            }
        }
    }

    private fun basesOf(declaration: ExternalDeclaration): List<ExternalDeclaration> {
        val texts = when (declaration) {
            is ExternalInterface -> declaration.supertypes
            is ExternalClass -> listOfNotNull(declaration.superClass) + declaration.interfaces
            else -> emptyList()
        }
        return texts.mapNotNull { byName[it.substringBefore('<').trim('`')] }
    }

    private fun ownMembers(declaration: ExternalDeclaration): List<ExternalMember> = when (declaration) {
        is ExternalInterface -> declaration.members
        is ExternalClass -> declaration.members
        else -> emptyList()
    }

    /** Member key: `p:` + name for a property, `f:` + [overloadSignature] for a function. */
    fun keyOf(member: ExternalMember): String? = when (member) {
        is ExternalProperty -> "p:" + member.name
        is ExternalFunction -> "f:" + overloadSignature(member.name, member.typeParameters, member.parameters)
        is SkippedMember -> null
    }

    /**
     * Every inherited member by key, nearest base first — the first base
     * declaring a key wins, which is what a redeclaration is compared against.
     */
    fun inherited(declaration: ExternalDeclaration): Map<String, ExternalMember> {
        val result = HashMap<String, ExternalMember>()
        val visited = HashSet<String>()
        fun walk(d: ExternalDeclaration) {
            val name = (d as? ExternalInterface)?.name ?: (d as? ExternalClass)?.name ?: return
            if (!visited.add(name)) return
            for (base in basesOf(d)) {
                for (member in ownMembers(base)) {
                    val key = keyOf(member) ?: continue
                    result.putIfAbsent(key, member)
                }
                walk(base)
            }
        }
        walk(declaration)
        return result
    }

    /**
     * For every CLASS name, the keys of its own members that some generated
     * declaration below it overrides — those must be `open` (a Kotlin class
     * member is final by default; an interface member is open already).
     */
    val openedByClass: Map<String, Set<String>> = run {
        val opened = HashMap<String, MutableSet<String>>()
        for (declaration in byName.values) {
            val inherited = inherited(declaration)
            for (member in ownMembers(declaration)) {
                val key = keyOf(member) ?: continue
                if (key !in inherited) continue
                // Attribute the override to the nearest base declaring the key.
                var found = false
                val visited = HashSet<String>()
                fun mark(d: ExternalDeclaration) {
                    if (found) return
                    val name = (d as? ExternalInterface)?.name ?: (d as? ExternalClass)?.name ?: return
                    if (!visited.add(name)) return
                    for (base in basesOf(d)) {
                        if (found) return
                        if (base is ExternalClass && base.members.any { keyOf(it) == key }) {
                            opened.getOrPut(base.name) { HashSet() }.add(key)
                            found = true
                            return
                        }
                        if (base is ExternalInterface && base.members.any { keyOf(it) == key }) {
                            found = true
                            return
                        }
                        mark(base)
                    }
                }
                mark(declaration)
            }
        }
        opened
    }

    private fun classNamed(supertype: String): ExternalClass? =
        byName[supertype.substringBefore('<').trim('`')] as? ExternalClass

    /**
     * (EXT.8) The constructor a class is CALLED with: its own when it declares
     * one, else the nearest generated base's — TypeScript inherits the
     * constructor of a class that declares none, and a consumer's
     * `Derived("x", 1)` must keep compiling. Null when nothing up the chain
     * declares one (Kotlin's implicit default matches TS's).
     */
    fun effectiveConstructor(declaration: ExternalClass): List<ExternalParameter>? {
        var current: ExternalClass? = declaration
        val visited = HashSet<String>()
        while (current != null && visited.add(current.name)) {
            current.constructorParameters?.let { return it }
            current = current.superClass?.let(::classNamed)
        }
        return null
    }

    /**
     * (EXT.8) The gate variant's superclass call (an external class omits it, a
     * plain Kotlin class may not): the INHERITED parameters passed through by
     * name when the class declares no constructor of its own, one `null!!` per
     * base parameter otherwise.
     */
    fun gateSuperCall(declaration: ExternalClass, superClass: String): String {
        val base = classNamed(superClass) ?: return "()"
        val baseParameters = effectiveConstructor(base).orEmpty()
        val arguments =
            if (declaration.constructorParameters == null) baseParameters.map { kotlinIdentifier(it.name) }
            else baseParameters.map { "null!!" }
        return arguments.joinToString(", ", prefix = "(", postfix = ")")
    }

}

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
    val inheritance = Inheritance(declarations)
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
                val supertypes =
                    if (declaration.supertypes.isEmpty()) ""
                    else declaration.supertypes.joinToString(", ", prefix = " : ")
                appendLine(
                    "public $keyword ${kotlinIdentifier(declaration.name)}$typeParams$supertypes {"
                )
                for (marker in declaration.headerMarkers) {
                    appendLine("    /* xtsc: $marker */")
                }
                val inherited = inheritance.inherited(declaration)
                for (member in declaration.members) {
                    appendMember(member, inherited = inherited[inheritance.keyOf(member)])
                }
                appendLine("}")
            }
            is ExternalTypeAlias -> {
                for (marker in declaration.markers) appendLine("/* xtsc: $marker */")
                val typeParams =
                    if (declaration.typeParameters.isEmpty()) ""
                    else declaration.typeParameters
                        .joinToString(", ", prefix = "<", postfix = ">") {
                            kotlinIdentifier(it)
                        }
                appendLine(
                    "public typealias ${kotlinIdentifier(declaration.name)}$typeParams = ${declaration.body}"
                )
            }
            is ExternalTopLevelValue -> {
                val keyword = if (declaration.readOnly) "val" else "var"
                val externalModifier = if (external) "external " else ""
                val body = if (external) "" else " = null!!"
                appendLine(
                    "public $externalModifier$keyword ${kotlinIdentifier(declaration.name)}: ${declaration.type}$body"
                )
            }
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
                // abstract/open, then external. (EXT.8) A non-abstract class is
                // `open` — Dukat's and kotlin-wrappers' convention for externals,
                // because JavaScript classes are always extensible and a
                // generated subclass (or a consumer's) must be able to say so.
                // The GATE variant renders every class `abstract`: a
                // non-external class implementing a generated interface would
                // otherwise owe implementations, and `abstract` also keeps it
                // extensible — both are things an external class gets for free.
                val abstractModifier = when {
                    !external -> "abstract "
                    declaration.isAbstract -> "abstract "
                    else -> "open "
                }
                val externalModifier = if (external) "external " else ""
                val typeParams =
                    if (declaration.typeParameters.isEmpty()) ""
                    else declaration.typeParameters
                        .joinToString(", ", prefix = "<", postfix = ">") {
                            kotlinIdentifier(it)
                        }
                val constructorText = inheritance.effectiveConstructor(declaration)
                    ?.joinToString(", ", prefix = "(", postfix = ")") {
                        "${kotlinIdentifier(it.name)}: ${it.type}"
                    } ?: ""
                // (EXT.8) Supertypes: the generated base class first (with the
                // gate variant's `null!!` constructor call — an external class
                // omits the call, a plain Kotlin class may not), then the
                // generated interfaces.
                val supertypeTexts = buildList {
                    declaration.superClass?.let { base ->
                        add(if (external) base else base + inheritance.gateSuperCall(declaration, base))
                    }
                    addAll(declaration.interfaces)
                }
                val supertypes =
                    if (supertypeTexts.isEmpty()) ""
                    else supertypeTexts.joinToString(", ", prefix = " : ")
                val header = "public $abstractModifier${externalModifier}class " +
                    "${kotlinIdentifier(declaration.name)}$typeParams$constructorText$supertypes"
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
                    val inherited = inheritance.inherited(declaration)
                    val opened = inheritance.openedByClass[declaration.name].orEmpty()
                    for (member in declaration.members) {
                        val key = inheritance.keyOf(member)
                        appendMember(
                            member,
                            needsBody = !external,
                            inherited = inherited[key],
                            open = key != null && key in opened,
                        )
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
    /** (EXT.8) The inherited member this one redeclares, if any — it renders `override`. */
    inherited: ExternalMember? = null,
    /** (EXT.8) A class member some generated subclass overrides — it renders `open`. */
    open: Boolean = false,
) {
    val body = if (needsBody) " = null!!" else ""
    val modifiers = buildString {
        if (inherited != null) append("override ")
        if (open) append("open ")
    }
    when (member) {
        is ExternalProperty -> {
            // Kotlin refuses `override val` over a `var`; TypeScript allows a
            // subinterface to add `readonly`. Rendered as the base's `var`,
            // loudly — the narrowing is a TypeScript-only fact.
            val baseIsVar = inherited is ExternalProperty && !inherited.readOnly
            if (member.readOnly && baseIsVar) {
                appendLine("${indent}/* xtsc: readonly narrows an inherited var - rendered var */")
            }
            val keyword = if (member.readOnly && !baseIsVar) "val" else "var"
            appendLine(
                "${indent}public $modifiers$keyword ${kotlinIdentifier(member.name)}: ${member.type}$body"
            )
        }
        is ExternalFunction -> {
            for (marker in member.markers) appendLine("${indent}/* xtsc: $marker */")
            val typeParams =
                if (member.typeParameters.isEmpty()) ""
                else member.typeParameters
                    .joinToString(", ", prefix = "<", postfix = "> ") {
                        kotlinIdentifier(it)
                    }
            val parameters = member.parameters.joinToString(", ") {
                "${kotlinIdentifier(it.name)}: ${it.type}"
            }
            appendLine(
                "${indent}public ${modifiers}fun $typeParams${kotlinIdentifier(member.name)}($parameters): ${member.returnType}$body"
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
