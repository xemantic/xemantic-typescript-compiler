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
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.NodeBase
import com.xemantic.typescript.compiler.SourceFile
import com.xemantic.typescript.compiler.Symbol
import com.xemantic.typescript.compiler.Type
import com.xemantic.typescript.compiler.TypeFlags
import com.xemantic.typescript.compiler.booleanType
import com.xemantic.typescript.compiler.numberType
import com.xemantic.typescript.compiler.stringType

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
 * consumer's vocabulary, the resolution is what the checker knows. (EXT.10)
 * adds the second chance, not a first: where the resolved body has no Kotlin
 * spelling — a generic instantiation, a function-typed body — a use names the
 * emitted alias instead of falling back. (EXT.11a) A CALLABLE interface — one
 * call signature and nothing else, or an empty interface over one such base —
 * is rendered through this same declaration: its Kotlin shape is a function
 * type, not an interface, and uses name it exactly as they name an alias.
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
    override val name: String,
    override val typeParameters: List<String>,
    /** Loud records — a constraint or default not carried — above the fun. */
    override val markers: List<String>,
    override val parameters: List<ExternalParameter>,
    override val returnType: String,
) : ExternalDeclaration, FunctionSignature

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
    override val name: String,
    /** (EXT.5) A generic METHOD's own type-parameter names, syntactic. */
    override val typeParameters: List<String>,
    /** (EXT.5) Loud per-member records — constraints/defaults not carried. */
    override val markers: List<String>,
    override val parameters: List<ExternalParameter>,
    override val returnType: String,
) : ExternalMember, FunctionSignature

/**
 * (EXT.12) The one view the overload collapse reads a function through —
 * a top-level function and a method (instance or static) are the same
 * shape to Kotlin's overload rule and to the rank that picks a class's
 * survivor ([overloadWinners]), so both model classes expose it and the
 * collapse is written ONCE.
 */
internal sealed interface FunctionSignature {
    val name: String
    /** The function's OWN type-parameter names. */
    val typeParameters: List<String>
    /** Loud records rendered above the declaration — constraints, defaults, a dropped `this`. */
    val markers: List<String>
    val parameters: List<ExternalParameter>
    val returnType: String
}

internal class ExternalParameter(
    val name: String,
    /**
     * Full Kotlin type text — for a [vararg] parameter the ELEMENT type
     * (`vararg xs: String`), never the array.
     */
    val type: String,
    /**
     * (EXT.11b) A DECLARATION's rest parameter `...xs: T[]` is Kotlin's
     * `vararg xs: T`: the one shape that keeps the call site's arity open, as
     * JavaScript's is. Rendered by [parameterText]; part of the
     * [overloadSignature] key, because `vararg xs: T` and `xs: Array<T>` are
     * distinct Kotlin signatures exactly as `...xs: T[]` and `xs: T[]` are
     * distinct TypeScript ones.
     */
    val vararg: Boolean = false,
)

/** (EXT.11b) The one rendering of a parameter: `[vararg ]name: type`. */
internal fun parameterText(parameter: ExternalParameter): String =
    (if (parameter.vararg) "vararg " else "") +
        "${kotlinIdentifier(parameter.name)}: ${parameter.type}"

/** A member (EXT.1) refuses — rendered as a marker, never dropped. */
internal class SkippedMember(val description: String) : ExternalMember

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
 *
 * (EXT.11c) A base's members are read THROUGH the supertype's type arguments:
 * `class D<U> : B<U>` inherits `B<T>`'s `fun f(x: T)` as `fun f(x: U)`, and
 * only that substituted form can be compared with `D`'s own redeclaration,
 * whose text is in `D`'s vocabulary — before this a renamed type parameter
 * hid every override down a generic chain (the (EXT.8) fixtures all spell
 * `T`, so nothing noticed). The substitution composes down the chain, and
 * the override decision itself is the measured [overrideSignature], not the
 * overload-conflict key (`KotlinSignatureKeys.kt` has the two tables).
 */
internal class Inheritance(declarations: List<ExternalDeclaration>) {

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

    /** The generated declaration a supertype text names, if any. */
    fun declarationNamed(supertype: String): ExternalDeclaration? =
        byName[supertype.substringBefore('<').trim('`')]

    private fun typeParametersOf(declaration: ExternalDeclaration): List<String> = when (declaration) {
        is ExternalInterface -> declaration.typeParameters
        is ExternalClass -> declaration.typeParameters
        else -> emptyList()
    }

    /**
     * A base with the substitution its members are read through: the base's
     * own type-parameter names mapped to the supertype's arguments, those
     * arguments themselves already in the DERIVED declaration's vocabulary
     * through [outer] (the substitution the derived declaration was reached
     * with). Arity mismatch — not producible, kept safe — maps nothing.
     */
    private class Base(val declaration: ExternalDeclaration, val substitution: Map<String, String>)

    private fun basesOf(declaration: ExternalDeclaration, outer: Map<String, String>): List<Base> {
        val texts = when (declaration) {
            is ExternalInterface -> declaration.supertypes
            is ExternalClass -> listOfNotNull(declaration.superClass) + declaration.interfaces
            else -> emptyList()
        }
        return texts.mapNotNull { text ->
            val base = declarationNamed(text) ?: return@mapNotNull null
            val parameters = typeParametersOf(base)
            val arguments = typeArgumentTexts(text).orEmpty().map { substituteTypeParameters(it, outer) }
            val substitution =
                if (parameters.size == arguments.size) parameters.zip(arguments).toMap() else emptyMap()
            Base(base, substitution)
        }
    }

    private fun ownMembers(declaration: ExternalDeclaration): List<ExternalMember> = when (declaration) {
        is ExternalInterface -> declaration.members
        is ExternalClass -> declaration.members
        else -> emptyList()
    }

    /** [member] with every type text read through [substitution]. */
    private fun substituted(member: ExternalMember, substitution: Map<String, String>): ExternalMember =
        if (substitution.isEmpty()) member else when (member) {
            is ExternalProperty -> ExternalProperty(
                name = member.name,
                type = substituteTypeParameters(member.type, substitution),
                readOnly = member.readOnly,
            )
            is ExternalFunction -> ExternalFunction(
                name = member.name,
                typeParameters = member.typeParameters,
                markers = member.markers,
                parameters = member.parameters.map { substitutedParameter(it, substitution) },
                returnType = substituteTypeParameters(member.returnType, substitution),
            )
            is SkippedMember -> member
        }

    private fun substitutedParameter(parameter: ExternalParameter, substitution: Map<String, String>) =
        ExternalParameter(parameter.name, substituteTypeParameters(parameter.type, substitution), parameter.vararg)

    /** Member key: `p:` + name for a property, `f:` + [overrideSignature] for a function. */
    fun keyOf(member: ExternalMember): String? = when (member) {
        is ExternalProperty -> "p:" + member.name
        is ExternalFunction -> "f:" + overrideSignature(member.name, member.typeParameters, member.parameters)
        is SkippedMember -> null
    }

    /** One inherited member: the base declaring it, its raw form there, and its form read through the chain. */
    private class InheritedMember(val base: ExternalDeclaration, val raw: ExternalMember, val seen: ExternalMember)

    /**
     * Every member inherited by [declaration], nearest base first, each in
     * the derived declaration's vocabulary — the first base declaring a key
     * wins, which is what a redeclaration is compared against.
     */
    private fun inheritedMembers(declaration: ExternalDeclaration): List<InheritedMember> {
        val result = mutableListOf<InheritedMember>()
        val visited = HashSet<String>()
        fun walk(d: ExternalDeclaration, outer: Map<String, String>) {
            val name = (d as? ExternalInterface)?.name ?: (d as? ExternalClass)?.name ?: return
            if (!visited.add(name)) return
            for (base in basesOf(d, outer)) {
                for (member in ownMembers(base.declaration)) {
                    result += InheritedMember(base.declaration, member, substituted(member, base.substitution))
                }
                walk(base.declaration, base.substitution)
            }
        }
        walk(declaration, emptyMap())
        return result
    }

    /** Every inherited member by key, nearest base first, in the derived declaration's vocabulary. */
    fun inherited(declaration: ExternalDeclaration): Map<String, ExternalMember> {
        val result = HashMap<String, ExternalMember>()
        for (member in inheritedMembers(declaration)) {
            val key = keyOf(member.seen) ?: continue
            result.putIfAbsent(key, member.seen)
        }
        return result
    }

    /**
     * For every CLASS name, the keys of its own members that some generated
     * declaration below it overrides — those must be `open` (a Kotlin class
     * member is final by default; an interface member is open already).
     * The override is attributed to the NEAREST base declaring the key, and
     * recorded under that base's own (raw) key, which is what the renderer
     * looks the base's members up by.
     */
    val openedByClass: Map<String, Set<String>> = run {
        val opened = HashMap<String, MutableSet<String>>()
        for (declaration in byName.values) {
            val inherited = inheritedMembers(declaration)
            for (member in ownMembers(declaration)) {
                val key = keyOf(member) ?: continue
                val nearest = inherited.firstOrNull { keyOf(it.seen) == key } ?: continue
                if (nearest.base is ExternalClass) {
                    val rawKey = keyOf(nearest.raw) ?: continue
                    opened.getOrPut(nearest.base.name) { HashSet() }.add(rawKey)
                }
            }
        }
        opened
    }

    private fun classNamed(supertype: String): ExternalClass? = declarationNamed(supertype) as? ExternalClass

    /**
     * (EXT.8) The constructor a class is CALLED with: its own when it declares
     * one, else the nearest generated base's — TypeScript inherits the
     * constructor of a class that declares none, and a consumer's
     * `Derived("x", 1)` must keep compiling. Null when nothing up the chain
     * declares one (Kotlin's implicit default matches TS's). An inherited
     * constructor's parameter types are read through the chain's type
     * arguments ((EXT.11c)), in the class's own vocabulary.
     */
    fun effectiveConstructor(declaration: ExternalClass): List<ExternalParameter>? {
        var current: ExternalClass? = declaration
        var substitution: Map<String, String> = emptyMap()
        val visited = HashSet<String>()
        while (current != null && visited.add(current.name)) {
            current.constructorParameters?.let { parameters ->
                return parameters.map { substitutedParameter(it, substitution) }
            }
            val superClass = current.superClass ?: return null
            val base = classNamed(superClass) ?: return null
            val arguments = typeArgumentTexts(superClass).orEmpty().map { substituteTypeParameters(it, substitution) }
            substitution =
                if (base.typeParameters.size == arguments.size) base.typeParameters.zip(arguments).toMap()
                else emptyMap()
            current = base
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
 *    ([nullableTypeText])
 *  - (EXT.11b) `any` and `unknown` -> `Any?`, with NO marker: the fallback
 *    text was already `Any?`, so what changes is that a composite carrying an
 *    `any` — `(err: any) => void`, `any[]`, `Observable<any>` — now maps as a
 *    whole instead of refusing. `Any?` is the honest Kotlin spelling of both
 *    (a JavaScript value of any type, null included). `errorType` and its
 *    `unresolved` sibling — intrinsics carrying the `any` FLAG under another
 *    name, i.e. a DEGRADED resolution — stay in the fallback: the test is
 *    the intrinsic's NAME, never the flag alone, because a degraded `any` is
 *    the silent direction this generator exists to refuse. The collector
 *    adds the other half of that evidence: a WRITTEN annotation that is not
 *    the `any` keyword and resolves to the bare `any` intrinsic anyway
 *    (`Record<string, number>` does, in this checker) never reaches this
 *    row — it keeps a marker naming what was written — so a resolved `any`
 *    maps only where it was spelled, or answered for an un-annotated value.
 *  - (EXT.11b) a LITERAL type widens to its base — `"N"` -> `String`, `1` ->
 *    `Double`, `true`/`false` -> `Boolean` ([widenLiteral], the widening
 *    `collectValue` has always applied to an un-annotated `const`): a Kotlin
 *    external has no literal types, and the base is what every mutable
 *    TypeScript position widens to anyway. A bigint literal stays a marker
 *    (no `bigint` mapping to widen to).
 *  - (EXT.11b) a UNION whose members other than `null`/`undefined` ALL map to
 *    ONE Kotlin text collapses to that text, nullable when a nullish member
 *    was dropped: `X | null`, `X | undefined`, `X | null | undefined` -> `X?`
 *    (exactly one survivor, the common case), and — the literal rule
 *    composed — `"N" | "E" | "C"` -> `String`, `"a" | "b" | null` -> `String?`.
 *    Two survivors mapping to DIFFERENT texts (`string | number`) keep the
 *    marker: Kotlin has no union, and `Any?` there would be a silent widening.
 *  - (EXT.11b) an ARRAY — `T[]` resolves to a [Type.Reference] whose target
 *    is the lib `Array<T>` interface, `ReadonlyArray<T>` to its sibling —
 *    -> `Array<T>` when the one argument maps, on POSITIVE lib evidence:
 *    the target's symbol is named `Array`/`ReadonlyArray` AND every one of
 *    its declarations sits in a `lib.*.d.ts` file ([isLibDeclared]); a
 *    program's own `Array`-named type is not an array. `ReadonlyArray` maps
 *    to the same `Array<T>` deliberately — Kotlin externals have no read-only
 *    array type, and `List<T>` would be a lie about the runtime object.
 *
 * ANY other type — a union of distinct texts, an intersection, an object
 * literal type, a generic the generation does not emit (`Promise<T>` among
 * them: the compile gate has no classpath and `kotlin.js.Promise` is not a
 * built-in, so it stays a marker until a platform rung), references to
 * non-generated interfaces — maps to the ONE documented fallback: `Any?`
 * followed by a marker comment carrying the checker's own rendering of the
 * type, so nothing is ever dropped silently. The fallback is already
 * nullable, which is why optionality does not add a second `?` to it.
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
        optional -> nullableTypeText(mapped)
        else -> mapped
    }
}

/**
 * (EXT.11b) The ONE nullable-wrapping rule, shared by every producer of a
 * nullable type text — an optional member or parameter, an optional method's
 * function-typed property, and the nullable-union rules on both the
 * syntactic and the resolved path — so no two of them can disagree about
 * the shape:
 *
 *  - a FUNCTION type is parenthesised, `((T) -> Unit)?` — `(T) -> Unit?`
 *    would make the RETURN nullable instead;
 *  - a text that is ALREADY nullable is left alone, so optionality composed
 *    with a nullable union (`p?: X | undefined`, the `.d.ts` idiom under
 *    `exactOptionalPropertyTypes`) renders `X?` ONCE, and `Any?` stays
 *    `Any?` — Kotlin refuses `X??`.
 *
 * "Already nullable" is decided on the TOP-LEVEL shape ([isFunctionTypeText]),
 * not on the last character: `(T) -> String?` ends in `?` and is a function
 * type whose RETURN is nullable, so it is wrapped to `((T) -> String?)?`;
 * `((T) -> String)?` has its arrow one level down and is left alone.
 */
internal fun nullableTypeText(mapped: String): String = when {
    isFunctionTypeText(mapped) -> "($mapped)?"
    mapped.endsWith("?") -> mapped
    else -> "$mapped?"
}

/**
 * (EXT.11b) Whether a Kotlin type text is a FUNCTION type at its top level —
 * an ` -> ` outside every `(…)` and `<…>` — as opposed to a name carrying one
 * inside its arguments (`Array<(T) -> Unit>`) or a function type already
 * parenthesised for nullability (`((T) -> Unit)?`). The arrow is tested
 * before the `>` it contains is counted as a closing bracket.
 */
private fun isFunctionTypeText(text: String): Boolean {
    var depth = 0
    var index = 0
    while (index < text.length) {
        if (text.startsWith(" -> ", index)) {
            if (depth == 0) return true
            index += 4
            continue
        }
        when (text[index]) {
            '(', '<' -> depth++
            ')', '>' -> depth--
        }
        index++
    }
    return false
}

/**
 * (EXT.11b) A literal type widened to its base primitive — the widening
 * TypeScript itself performs at every mutable position, and the only shape
 * a Kotlin external can carry. Shared by `collectValue` (an un-annotated
 * `export const RETRIES = 3` is typed `3`) and [mappedText] (a literal
 * annotation, a member of a literal union). A bigint literal is left as it
 * is: there is no `bigint` mapping for it to widen to, so it stays a marker.
 */
internal fun widenLiteral(type: Type): Type = when (type) {
    is Type.StringLiteral -> stringType
    is Type.NumberLiteral -> numberType
    is Type.Intrinsic ->
        if (TypeFlags.BooleanLiteral in type.flags) booleanType else type
    else -> type
}

/** (EXT.11b) The `null` and `undefined` intrinsics — the members a nullable union drops. */
internal fun isNullishType(type: Type): Boolean =
    type is Type.Intrinsic && (TypeFlags.Null in type.flags || TypeFlags.Undefined in type.flags)

/**
 * (EXT.11b) The positive LIB evidence behind an array mapping: [symbol] has
 * at least one declaration and every one of them sits in a source file whose
 * base name is `lib.<…>.d.ts` — `Checker.isLibFileName`'s own predicate,
 * applied to the [SourceFile] reached by walking [NodeBase.parent] up from
 * the declaration (stamped by every parse, lib parses included). A symbol a
 * program declared, or MERGED into (a global-script `interface Array<T>`
 * augmentation adopts the program's declaration beside the lib's), answers
 * false and keeps the fallback: a name is never evidence.
 */
internal fun isLibDeclared(symbol: Symbol): Boolean {
    val declarations = symbol.declarations
    if (declarations.isEmpty()) return false
    return declarations.all { declaration ->
        var node: Node? = declaration
        while (node != null && node !is SourceFile) node = (node as? NodeBase)?.parent
        if (node !is SourceFile) return false
        val base = node.fileName.substringAfterLast('/').substringAfterLast('\\')
        base.startsWith("lib.") && base.endsWith(".d.ts")
    }
}

/** (EXT.11b) The lib array interfaces an `Array<T>` mapping may name. */
internal val libArrayNames: Set<String> = setOf("Array", "ReadonlyArray")

/**
 * (EXT.11b) Whether [symbol] is the lib `Array`/`ReadonlyArray` interface —
 * the name AND [isLibDeclared], so a program's own `Array`-named type never
 * qualifies.
 */
internal fun isLibArraySymbol(symbol: Symbol?): Boolean =
    symbol != null && symbol.name in libArrayNames && isLibDeclared(symbol)

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
    // (EXT.11b) A literal type is its base primitive here.
    mappedIntrinsic(widenLiteral(type), returnPosition)?.let { return it }
    // The enclosing declaration's own type parameter, by its written name.
    if (type is Type.TypeParam) {
        val name = type.symbol?.name ?: return null
        return if (name in scope.ownTypeParams) kotlinIdentifier(name) else null
    }
    // (EXT.11b) A union collapses to the ONE text its non-nullish members
    // map to, nullable when a nullish member was dropped; distinct texts
    // keep the marker. `never` is not a member Kotlin can drop, so `X |
    // never` refuses as any other two-member union does (the checker reduces
    // it away before it gets here in every ordinary case).
    if (type is Type.Union) {
        val members = type.types.filterNot(::isNullishType)
        val texts = members.mapTo(LinkedHashSet()) { member ->
            mappedText(member, returnPosition = false, scope) ?: return null
        }
        val text = texts.singleOrNull() ?: return null
        return if (members.size < type.types.size) nullableTypeText(text) else text
    }
    // A reference to an interface THIS generation emits — a bare use...
    scope.generatedNameOf(type)?.let { name ->
        // (EXT.11a) The ARITY guard: a generated target that DECLARES type
        // parameters is rendered only as an instantiation. The bare
        // `Type.Interface` of a generic class reaches here through a VALUE
        // typed by the class itself — `export const ctor = Box` where `Box` is
        // generic — because this checker types a class value as its INSTANCE
        // type (CHK.73: a class value has no static-side type here), and the
        // un-instantiated instance type has no Kotlin spelling: `val ctor:
        // Box` is a compile error (`one type argument expected`), which is
        // the loud direction only by luck of Kotlin's own check. The
        // `resolvedTypeArguments == null` leg is the same question one
        // constructor over; a target without parameters keeps its bare name.
        if (type !is Type.Reference) {
            if (type is Type.Interface && !type.typeParameters.isNullOrEmpty()) return null
            return kotlinIdentifier(name)
        }
        // ...or a generic instantiation, rendered only when EVERY argument
        // maps: one unmappable argument falls the whole reference back, so a
        // half-translated `Box<...>` never appears.
        val args = type.resolvedTypeArguments
            ?: return if (type.target.typeParameters.isNullOrEmpty()) kotlinIdentifier(name) else null
        val mappedArgs = args.map { argument ->
            mappedText(argument, returnPosition = false, scope) ?: return null
        }
        return if (mappedArgs.isEmpty()) kotlinIdentifier(name)
        else "${kotlinIdentifier(name)}<${mappedArgs.joinToString(", ")}>"
    }
    // (EXT.11b) The lib array, after the generated-name leg so that an
    // exported `Array`-named interface of the program is consulted first —
    // and refused by [isLibArraySymbol] anyway, since its declaration is not
    // in a lib file. Exactly one argument: the lib declares no other arity,
    // and a mis-declared one is not an array.
    if (type is Type.Reference && isLibArraySymbol(type.target.symbol)) {
        val argument = type.resolvedTypeArguments?.singleOrNull() ?: return null
        val element = mappedText(argument, returnPosition = false, scope) ?: return null
        return "Array<$element>"
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
        // (EXT.11b) By NAME: `errorType`/`unresolvedType` carry the same
        // flag and must stay marked.
        TypeFlags.Any in type.flags && type.intrinsicName == "any" -> "Any?"
        TypeFlags.Unknown in type.flags -> "Any?"
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
                val parameters = declaration.parameters.joinToString(", ", transform = ::parameterText)
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
                    ?.joinToString(", ", prefix = "(", postfix = ")", transform = ::parameterText) ?: ""
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
            // (EXT.11c) A `var` override must repeat the inherited type EXACTLY
            // (measured: `Box<Any?>` over `Box<Any?>?` is refused, nullability
            // alone), where TypeScript lets a subclass REDECLARE a mutable
            // member narrower — `ConnectableObservable<T>.source:
            // Observable<T>` over `Observable.source: Observable<any> |
            // undefined`. Rendered as the inherited type, loudly, the narrowing
            // named. A `val` override is covariant in Kotlin, so a narrowed
            // readonly member over a readonly base keeps its own type.
            val type =
                if (baseIsVar && typeTextsDiffer(member.type, inherited.type)) {
                    appendLine(
                        "${indent}/* xtsc: narrowed to ${typeTextWithoutMarker(member.type)} in TypeScript" +
                            " - rendered as the inherited ${typeTextWithoutMarker(inherited.type)} */"
                    )
                    inherited.type
                } else {
                    member.type
                }
            appendLine(
                "${indent}public $modifiers$keyword ${kotlinIdentifier(member.name)}: $type$body"
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
            val parameters = member.parameters.joinToString(", ", transform = ::parameterText)
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
