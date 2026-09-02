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

/**
 * (EXT.11c) The two SIGNATURE KEYS Kotlin decides a generated surface by —
 * the OVERLOAD-CONFLICT key ([overloadSignature]) and the OVERRIDE key
 * ([overrideSignature]) — and the type-text machinery both are computed
 * from: a parser over the Kotlin type text THIS generator renders
 * ([parseKotlinTypeText]), the canonical form each key needs, and the
 * type-parameter substitution an inherited member is read through
 * ([substituteTypeParameters]).
 *
 * ## Why two keys, measured
 *
 * The (EXT.5) key was the TEXT of the signature — type-parameter NAMES and
 * `?` included — and the whole `rxjs@7.8.2` surface compiled with 24
 * `Conflicting overloads` that key could not see. Kotlin's equivalence is
 * NOT textual, and it is not the one an obvious "strip the names and the
 * `?`" would produce either; it was derived EMPIRICALLY against the
 * metadata compiler (`KotlinOverloadEquivalenceTest` pins every row of the
 * table against the compiler this build uses). Two functions of one name
 * CONFLICT exactly when:
 *
 *  1. their own type-parameter lists have the SAME LENGTH (`<A> f(x: Any?)`
 *     and `<A, R> f(x: Any?)` are distinct overloads; the names and the
 *     bounds play no part in the count);
 *  2. their value-parameter lists have the same length with the same
 *     `vararg`-ness per position (`vararg x: T` and `x: Array<T>` are
 *     distinct; parameter NAMES are not part of it, nor is the return type);
 *  3. the parameter types are equal under the following reading of the
 *     function's OWN type parameters, with everything else compared
 *     structurally — nullability included: `f(x: String)` and `f(x:
 *     String?)` are distinct overloads, and an ENCLOSING class's type
 *     parameter is an ordinary named type (`class C<T> { f(x: T); f(x:
 *     Any?) }` is legal).
 *
 *     An own type parameter is FREE when EVERY occurrence of it in the
 *     parameter list sits in a COVARIANT position — the top level, a
 *     function type's RETURN, a `vararg` element (`Array<out T>`), with the
 *     variance COMPOSED through nesting (a parameter of a parameter is
 *     covariant again: `((T) -> Unit) -> Unit` erases). A free type
 *     parameter reads as its BOUND with the occurrence's nullability
 *     applied; externals never emit bounds, so it is `Any?` for `T` and for
 *     `T?` alike — `<T> f(x: T)`, `<U> f(x: U?)` and `<A> f(x: Any?)` are
 *     ONE overload, and so are `<T, U> f(a: T, b: U)` and `<U, T> f(a: T,
 *     b: U)` and `<A, B> f(a: A, b: A)`. (Measured with a bound too: `<T :
 *     Base> f(x: T)` conflicts with `<U> f(x: Base)` and not with `<U> f(x:
 *     Base?)`.)
 *
 *     An own type parameter is PINNED when some occurrence sits in an
 *     INVARIANT or CONTRAVARIANT position — a type argument of a generated
 *     class or interface or of `Array` (all invariant), a function type's
 *     PARAMETER or RECEIVER. A pinned type parameter keeps its identity at
 *     EVERY occurrence, top-level ones included, with the occurrence's
 *     nullability: `Box<T>` is distinct from `Box<Any?>` and from `Box<U?>`,
 *     `(T) -> Unit` from `(Any?) -> Unit`, and `<T> f(a: T, b: Box<T>)` from
 *     `<U> f(a: Any?, b: Box<U>)` (the top-level `T` does not erase, because
 *     `T` is pinned by `Box<T>`). Pinned identities are compared up to a
 *     consistent RENAMING — `Box<T>` conflicts with `Box<U>`, `(Box<T>,
 *     Box<U>)` with `(Box<B>, Box<A>)` but NOT with `(Box<A>, Box<A>)` —
 *     which the key canonicalises by numbering pinned parameters in order
 *     of first occurrence.
 *
 * The OVERRIDE relation is a different, stricter one, and using the
 * conflict key for it would render `override` where Kotlin reports
 * `overrides nothing`: an override matches own type parameters
 * POSITIONALLY (`fun <U> f(x: Box<U>)` is overridden by `fun <T> f(x:
 * Box<T>)`) and demands exact nullability — `fun <T> f(x: T)` does NOT
 * override `fun <U> f(x: Any?)`, and, measured, a class may declare it
 * BESIDE that inherited member with no conflict at all. So the collector
 * collapses by [overloadSignature] and the renderer decides `override`/
 * `open` by [overrideSignature]; the two agree on every signature without
 * own type parameters, and deliberately not otherwise.
 *
 * ## The collapse's survivor
 *
 * (EXT.12) The key says WHICH declarations are one Kotlin overload; which
 * of them is rendered is a second, separate decision — the one with the
 * fewest markers, ties to the first declared ([overloadWinners],
 * [markerCount]) — and the dropped ones name it ([overloadCollapseDescription]).
 *
 * ## The type text
 *
 * The keys are computed from the RENDERED Kotlin type text because that is
 * what the model carries (strings only, by design — no checker object
 * survives the walk). The text is this generator's own, so its grammar is
 * small and closed: a name, backticked or not, with optional `<…>`
 * arguments and an optional `?`; a function type `(A, B) -> R`, optionally
 * with a receiver `Recv.(A) -> R` (a function-typed receiver
 * parenthesised), wrapped `(…)?` when nullable; and the fallback `Any?`
 * followed by a `/* xtsc: … */` marker, which is not part of the type Kotlin
 * sees and is stripped before parsing. A text the parser does not recognise
 * keeps the old TEXTUAL key rather than failing — never a crash, and the
 * gates say when that fallback is reached.
 */
internal sealed interface KotlinTypeText {
    val nullable: Boolean
}

/** A named type with its type arguments: `Observable<T>`, `String?`, a bare `T`, a dotted `server.Node` ((EXT.14)). */
internal data class NamedTypeText(
    val name: String,
    val arguments: List<KotlinTypeText>,
    override val nullable: Boolean,
) : KotlinTypeText

/** A function type, receiver included: `Recv.(A, B) -> R`, `((T) -> Unit)?`. */
internal data class FunctionTypeText(
    val receiver: KotlinTypeText?,
    val parameters: List<KotlinTypeText>,
    val returnType: KotlinTypeText,
    override val nullable: Boolean,
) : KotlinTypeText

/** The marker a fallback type text carries, split off before any parse. */
private const val MARKER_START = " /* xtsc:"

/**
 * Parses one rendered Kotlin type text, the marker comment stripped, or
 * answers null for a text outside the generator's grammar.
 */
internal fun parseKotlinTypeText(text: String): KotlinTypeText? =
    TypeTextParser(text.substringBefore(MARKER_START).trim()).parseWhole()

private class TypeTextParser(private val text: String) {

    private var index = 0

    fun parseWhole(): KotlinTypeText? {
        val result = try {
            type()
        } catch (_: IllegalStateException) {
            return null
        }
        skipSpaces()
        return if (index == text.length) result else null
    }

    private fun peek(): Char? = text.getOrNull(index)

    private fun skipSpaces() {
        while (peek() == ' ') index++
    }

    private fun startsWith(s: String): Boolean = text.startsWith(s, index)

    private fun expect(c: Char) {
        check(peek() == c)
        index++
    }

    private fun consumeIf(c: Char): Boolean {
        if (peek() != c) return false
        index++
        return true
    }

    private fun typeList(close: Char): List<KotlinTypeText> {
        val items = mutableListOf<KotlinTypeText>()
        skipSpaces()
        if (peek() != close) {
            do {
                items += type()
                skipSpaces()
            } while (consumeIf(','))
        }
        expect(close)
        return items
    }

    /**
     * (EXT.14) A name, DOTTED where the generator spells a nested type from
     * outside its object (`server.Node`, `server.protocol.Request` — each
     * segment backticked on its own by [shortestSpelling]). A `.` followed
     * by `(` is the receiver syntax of a function type and is left to
     * [type]. Before this a dotted name did not parse at all, so every key
     * over one fell to the textual form and a nested alias whose body spells
     * a qualified type could not be substituted at its use.
     */
    private fun identifier(): String {
        skipSpaces()
        val name = StringBuilder(segment())
        while (peek() == '.' && text.getOrNull(index + 1)?.let { it.isLetter() || it == '_' || it == '`' } == true) {
            index++
            name.append('.').append(segment())
        }
        return name.toString()
    }

    private fun segment(): String {
        if (consumeIf('`')) {
            val end = text.indexOf('`', index)
            check(end > index)
            val name = text.substring(index, end)
            index = end + 1
            return "`$name`"
        }
        val start = index
        while (true) {
            val c = peek() ?: break
            if (c.isLetterOrDigit() || c == '_') index++ else break
        }
        check(index > start)
        return text.substring(start, index)
    }

    private fun functionTail(receiver: KotlinTypeText?, parameters: List<KotlinTypeText>): KotlinTypeText {
        skipSpaces()
        check(startsWith("->"))
        index += 2
        return FunctionTypeText(receiver, parameters, type(), nullable = false)
    }

    private fun type(): KotlinTypeText {
        skipSpaces()
        var result: KotlinTypeText
        if (consumeIf('(')) {
            val items = typeList(')')
            skipSpaces()
            result = if (startsWith("->")) functionTail(null, items)
            else items.singleOrNull() ?: throw IllegalStateException()
        } else {
            val name = identifier()
            val arguments = if (consumeIf('<')) typeList('>') else emptyList()
            result = NamedTypeText(name, arguments, nullable = false)
        }
        skipSpaces()
        if (startsWith(".(")) {
            index += 2
            result = functionTail(result, typeList(')'))
        }
        while (consumeIf('?')) result = result.withNullable()
        return result
    }

}

private fun KotlinTypeText.withNullable(): KotlinTypeText = when (this) {
    is NamedTypeText -> copy(nullable = true)
    is FunctionTypeText -> copy(nullable = true)
}

/** (EXT.13) The non-null form of a type text, for the subtype test. */
internal fun KotlinTypeText.withoutNullable(): KotlinTypeText = when (this) {
    is NamedTypeText -> copy(nullable = false)
    is FunctionTypeText -> copy(nullable = false)
}

/**
 * The Kotlin text of a parsed type, in exactly the shape the generator
 * renders — the round trip is what lets a SUBSTITUTED inherited type be
 * compared with a redeclaration's text by structure rather than by luck.
 */
internal fun KotlinTypeText.toKotlinText(): String = when (this) {
    is NamedTypeText -> buildString {
        append(name)
        if (arguments.isNotEmpty()) arguments.joinTo(this, ", ", "<", ">") { it.toKotlinText() }
        if (nullable) append('?')
    }
    is FunctionTypeText -> {
        val core = buildString {
            receiver?.let { append(if (it is FunctionTypeText) "(${it.toKotlinText()})." else "${it.toKotlinText()}.") }
            parameters.joinTo(this, ", ", "(", ")") { it.toKotlinText() }
            append(" -> ").append(returnType.toKotlinText())
        }
        if (nullable) "($core)?" else core
    }
}

/**
 * The variance of a position in a type, composed through nesting: a
 * function type's return keeps the enclosing variance, its parameters and
 * receiver flip it, a type argument of a (generated, `Array`) class is
 * invariant whatever encloses it, and a `vararg` element is `out`.
 */
private enum class Variance {
    OUT, IN, INV;

    fun then(inner: Variance): Variance = when {
        this == INV || inner == INV -> INV
        this == inner -> OUT
        else -> IN
    }
}

/**
 * The canonical form of one parameter type for a key: [ownTypeParameter]
 * answers the spelling of a bare own type parameter at a position, given
 * its name, the occurrence's nullability and the position's variance;
 * every other named type and every function type render structurally.
 */
private fun canonical(
    type: KotlinTypeText,
    variance: Variance,
    ownTypeParameters: Set<String>,
    ownTypeParameter: (name: String, nullable: Boolean, variance: Variance) -> String,
): String = when (type) {
    is NamedTypeText ->
        if (type.arguments.isEmpty() && type.name in ownTypeParameters) {
            ownTypeParameter(type.name, type.nullable, variance)
        } else buildString {
            append(type.name)
            if (type.arguments.isNotEmpty()) {
                type.arguments.joinTo(this, ",", "<", ">") {
                    canonical(it, variance.then(Variance.INV), ownTypeParameters, ownTypeParameter)
                }
            }
            if (type.nullable) append('?')
        }
    is FunctionTypeText -> buildString {
        append('{')
        type.receiver?.let {
            append(canonical(it, variance.then(Variance.IN), ownTypeParameters, ownTypeParameter)).append('.')
        }
        type.parameters.joinTo(this, ",", "(", ")") {
            canonical(it, variance.then(Variance.IN), ownTypeParameters, ownTypeParameter)
        }
        append("->")
        append(canonical(type.returnType, variance.then(Variance.OUT), ownTypeParameters, ownTypeParameter))
        append('}')
        if (type.nullable) append('?')
    }
}

/** Every own-type-parameter occurrence in [type] with the variance of its position. */
private fun collectOccurrences(
    type: KotlinTypeText,
    variance: Variance,
    ownTypeParameters: Set<String>,
    into: MutableList<Pair<String, Variance>>,
) {
    when (type) {
        is NamedTypeText -> {
            if (type.arguments.isEmpty() && type.name in ownTypeParameters) into += type.name to variance
            for (argument in type.arguments) {
                collectOccurrences(argument, variance.then(Variance.INV), ownTypeParameters, into)
            }
        }
        is FunctionTypeText -> {
            type.receiver?.let { collectOccurrences(it, variance.then(Variance.IN), ownTypeParameters, into) }
            for (parameter in type.parameters) {
                collectOccurrences(parameter, variance.then(Variance.IN), ownTypeParameters, into)
            }
            collectOccurrences(type.returnType, variance.then(Variance.OUT), ownTypeParameters, into)
        }
    }
}

/** A parameter's type parsed; a value parameter's position is covariant (`out`), a `vararg` element's too. */
private class ParsedParameter(val type: KotlinTypeText, val vararg: Boolean)

/**
 * The parsed parameter list, or null when ANY type text is outside the
 * grammar — the keys then fall back to the textual form as a whole, so a
 * half-parsed signature never meets a fully parsed one.
 */
private fun parseParameters(parameters: List<ExternalParameter>): List<ParsedParameter>? =
    parameters.map { parameter ->
        ParsedParameter(parseKotlinTypeText(parameter.type) ?: return null, parameter.vararg)
    }

/** The (EXT.5) textual key — the fallback when a type text does not parse. */
private fun textualSignature(
    name: String,
    typeParameters: List<String>,
    parameters: List<ExternalParameter>,
): String = buildString {
    append(name)
    append('<').append(typeParameters.joinToString(","))
    append(">(")
    parameters.joinTo(this, ",") {
        (if (it.vararg) "vararg " else "") + it.type.substringBefore(MARKER_START)
    }
    append(')')
}

/**
 * (EXT.5, re-derived by (EXT.11c)) The OVERLOAD-CONFLICT key of a function:
 * the name, the NUMBER of own type parameters, and the parameter types in
 * the canonical form of the measured equivalence — a free own type
 * parameter as `Any?`, a pinned one as `#k` by first occurrence with its
 * nullability, `vararg` marked, markers stripped (two literal types both
 * falling to `Any?` conflict however different their markers read).
 * Two signatures with equal keys are one Kotlin overload; the collector
 * keeps the one [overloadWinners] picks and marks the rest.
 */
internal fun overloadSignature(
    name: String,
    typeParameters: List<String>,
    parameters: List<ExternalParameter>,
): String {
    val parsed = parseParameters(parameters) ?: return textualSignature(name, typeParameters, parameters)
    val own = typeParameters.toSet()
    val occurrences = mutableListOf<Pair<String, Variance>>()
    for (parameter in parsed) collectOccurrences(parameter.type, Variance.OUT, own, occurrences)
    val pinned = occurrences.filter { it.second != Variance.OUT }.mapTo(HashSet()) { it.first }
    val numbering = LinkedHashMap<String, Int>()
    val render = { parameterName: String, nullable: Boolean, _: Variance ->
        if (parameterName in pinned) {
            val index = numbering.getOrPut(parameterName) { numbering.size }
            "#$index" + (if (nullable) "?" else "")
        } else {
            // The bound, `Any?` (externals emit none), nullable already.
            "Any?"
        }
    }
    return buildString {
        append(name).append('<').append(typeParameters.size).append(">(")
        parsed.joinTo(this, ",") { parameter ->
            (if (parameter.vararg) "vararg " else "") + canonical(parameter.type, Variance.OUT, own, render)
        }
        append(')')
    }
}

/**
 * (EXT.12) The number of loud records a signature would render — the
 * `xtsc:` marker comments inside its parameter types and its return type,
 * plus its own marker list (constraints and defaults not carried, a dropped
 * `this`). The RANK of a member within an overload equivalence class: the
 * one with the fewest markers is the one whose Kotlin spelling lost the
 * least of the TypeScript, so it is the one a consumer should be handed.
 */
internal fun markerCount(signature: FunctionSignature): Int =
    signature.parameters.sumOf { countMarkers(it.type) } +
        countMarkers(signature.returnType) +
        signature.markers.size

private fun countMarkers(text: String): Int {
    var count = 0
    var index = text.indexOf(MARKER_START)
    while (index >= 0) {
        count++
        index = text.indexOf(MARKER_START, index + MARKER_START.length)
    }
    return count
}

/**
 * (EXT.12) For every candidate of a list — a class's members, the program's
 * top-level declarations — the index of the member of its overload
 * equivalence class that is KEPT, or the candidate's own index where it is
 * kept itself or is not a function at all (a `null` slot). Written once for
 * both sites ([markerCount] is the rank, [overloadSignature] the class).
 *
 * The policy, which (EXT.11c) recorded and did not take: two overloads
 * whose [overloadSignature]s are equal are one Kotlin overload, and the
 * survivor is the one with the FEWEST markers — a tie keeps the FIRST in
 * declaration order, which is what the policy was before, so every
 * equally-marked class renders exactly as it did. First-wins alone kept
 * rxjs's marked `<A> of(...valuesAndScheduler: Any?)` and dropped the
 * clean `<T> of(value: T): Observable<T>` declared four lines below it,
 * because the marked one came first: a marker describes what was LOST, and
 * the member losing least is the one to keep.
 *
 * A class is collected over the WHOLE list before any decision — an
 * equivalence class spans consecutive AND non-consecutive declarations
 * (rxjs's `zip` overloads interleave with a differently-keyed twin, and a
 * class spans files at the module surface), so a running "seen" set, which
 * decides at the first repeat, cannot express a later winner.
 *
 * Position is decided by the caller and is the same at both sites: every
 * member keeps its DECLARED slot — the kept one renders where it was
 * declared, each dropped one is a marker where IT was declared. Not "the
 * kept member moves into the class's first slot": a member of an
 * interleaved class would then move past members of OTHER classes, and the
 * rendered order would stop being the source order — with every slot kept,
 * rendered slot `i` is declaration `i` whatever the policy picks, the
 * marker for a dropped declaration stands where a reader of the `.d.ts`
 * expects it, and nothing downstream reads position (`override`/`open` are
 * keyed). The accessor-pair precedent (emit at the first accessor) is a
 * different case: a pair IS one member and has no second slot to keep.
 */
internal fun overloadWinners(candidates: List<FunctionSignature?>): IntArray {
    val winners = IntArray(candidates.size) { it }
    val classes = LinkedHashMap<String, MutableList<Int>>()
    candidates.forEachIndexed { index, candidate ->
        if (candidate == null) return@forEachIndexed
        val key = overloadSignature(candidate.name, candidate.typeParameters, candidate.parameters)
        classes.getOrPut(key) { mutableListOf() }.add(index)
    }
    for (members in classes.values) {
        var winner = members.first()
        var fewest = markerCount(candidates[winner]!!)
        for (index in members) {
            val count = markerCount(candidates[index]!!)
            // Strictly fewer: a tie keeps the earlier declaration.
            if (count < fewest) {
                winner = index
                fewest = count
            }
        }
        for (index in members) winners[index] = winner
    }
    return winners
}

/**
 * (EXT.12) The marker text of a dropped overload, naming the signature it
 * collapsed INTO — its own type parameters, name and parameter list as
 * Kotlin sees them, markers stripped (a comment-close inside the marker
 * would end it early) and the return type omitted (the class is decided
 * on the parameters).
 */
internal fun overloadCollapseDescription(kept: FunctionSignature): String {
    val typeParameters =
        if (kept.typeParameters.isEmpty()) ""
        else kept.typeParameters.joinToString(", ", prefix = "<", postfix = "> ") { kotlinIdentifier(it) }
    val parameters = kept.parameters.joinToString(", ") { parameter ->
        (if (parameter.vararg) "vararg " else "") +
            "${kotlinIdentifier(parameter.name)}: ${typeTextWithoutMarker(parameter.type)}"
    }
    return "overload of ${kept.name} collapsing to a duplicate signature - kept " +
        commentSafe("$typeParameters${kotlinIdentifier(kept.name)}($parameters)")
}

/**
 * (EXT.11c) The OVERRIDE key of a function — what decides `override` and
 * `open` in the renderer: the name, the number of own type parameters, and
 * the parameter types with an own type parameter spelled by its POSITION in
 * the declaration's own list and its exact nullability, no erasure. An
 * enclosing declaration's type parameter is an ordinary name here too; the
 * renderer substitutes a base's names by the supertype's arguments before
 * comparing ([substituteTypeParameters]).
 */
internal fun overrideSignature(
    name: String,
    typeParameters: List<String>,
    parameters: List<ExternalParameter>,
): String {
    val parsed = parseParameters(parameters) ?: return textualSignature(name, typeParameters, parameters)
    val own = typeParameters.toSet()
    val render = { parameterName: String, nullable: Boolean, _: Variance ->
        "#${typeParameters.indexOf(parameterName)}" + (if (nullable) "?" else "")
    }
    return buildString {
        append(name).append('<').append(typeParameters.size).append(">(")
        parsed.joinTo(this, ",") { parameter ->
            (if (parameter.vararg) "vararg " else "") + canonical(parameter.type, Variance.OUT, own, render)
        }
        append(')')
    }
}

/**
 * (EXT.11c) [text] with every bare occurrence of a name in [substitution]
 * replaced by the mapped type text — a base declaration's type parameters
 * read through the supertype's arguments (`Observable<T>`'s `source:
 * Observable<Any?>?` seen from `ConnectableObservable<U> : Observable<U>`
 * stays as it is; its `subscribe(observer: Observer<T>)` becomes
 * `Observer<U>`). Nullability composes: `T?` with `T -> X` is `X?`. The
 * marker comment, if any, is carried over verbatim; a text outside the
 * grammar is returned unchanged.
 */
internal fun substituteTypeParameters(text: String, substitution: Map<String, String>): String {
    if (substitution.isEmpty()) return text
    val markerIndex = text.indexOf(MARKER_START)
    val marker = if (markerIndex < 0) "" else text.substring(markerIndex)
    val parsed = parseKotlinTypeText(text) ?: return text
    val mapped = substitution.mapValues { (_, value) -> parseKotlinTypeText(value) ?: return text }
    return substitute(parsed, mapped).toKotlinText() + marker
}

private fun substitute(type: KotlinTypeText, substitution: Map<String, KotlinTypeText>): KotlinTypeText = when (type) {
    is NamedTypeText -> {
        val replacement = if (type.arguments.isEmpty()) substitution[type.name] else null
        when {
            replacement == null -> type.copy(arguments = type.arguments.map { substitute(it, substitution) })
            type.nullable && !replacement.nullable -> replacement.withNullable()
            else -> replacement
        }
    }
    is FunctionTypeText -> type.copy(
        receiver = type.receiver?.let { substitute(it, substitution) },
        parameters = type.parameters.map { substitute(it, substitution) },
        returnType = substitute(type.returnType, substitution),
    )
}

/**
 * (EXT.11c) Whether two rendered type texts denote DIFFERENT Kotlin types
 * — compared by structure with the markers stripped, so `Any? /* xtsc:
 * unmapped X */` and `Any? /* xtsc: unmapped Y */` are the same `Any?`.
 * Texts outside the grammar compare as text.
 */
internal fun typeTextsDiffer(a: String, b: String): Boolean {
    val parsedA = parseKotlinTypeText(a)
    val parsedB = parseKotlinTypeText(b)
    if (parsedA == null || parsedB == null) {
        return a.substringBefore(MARKER_START) != b.substringBefore(MARKER_START)
    }
    return parsedA != parsedB
}

/** The type text with its marker comment stripped — what Kotlin sees. */
internal fun typeTextWithoutMarker(text: String): String = text.substringBefore(MARKER_START)

/**
 * The top-level type ARGUMENTS of a rendered supertype text (`Observable<T>`
 * → `[T]`, `Base` → `[]`), for the substitution a base is read through;
 * null for a text outside the grammar.
 */
internal fun typeArgumentTexts(supertype: String): List<String>? =
    (parseKotlinTypeText(supertype) as? NamedTypeText)?.arguments?.map { it.toKotlinText() }
