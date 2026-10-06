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

package com.xemantic.typescript.compiler

/**
 * (P18.288) (CHK.216) part 2 — `infer` in a conditional type's `extends` when the pattern is a
 * TUPLE (`[infer H, ...infer T]`, `readonly [...infer I, infer L]`) or a TEMPLATE LITERAL
 * (`` `${infer H}${infer R}` ``, `` `${infer N extends number}` ``).
 *
 * tsgo infers the pattern's type parameters (`inferFromObjectTypes`' tuple arm, the variadic slice
 * `sliceTupleType`, `inferToTemplateLiteralType`) and then relates the check type to the instantiated
 * `extends` type. This model has no inference context for a conditional, so the pattern is MATCHED
 * structurally against the (concrete) check type instead, with the same answers on the shapes
 * modelled here; [match] answers:
 * - a binding map — the pattern matched, the true branch is taken with these bindings;
 * - [NO_MATCH] — definitely not related, the false branch is taken;
 * - null — a shape not modelled (a generic or `any` source, a duplicate `infer` name, an `infer`
 *   in a position other than a tuple slot / template span …): the caller keeps its old behaviour.
 */
internal class ConditionalInferPatterns(private val checker: Checker) {

    /** Whether the [extendsType] node is a pattern [match] decides: a tuple (optionally `readonly`) or a
     *  template literal carrying at least one `infer`. */
    fun isPattern(extendsType: TypeNode): Boolean {
        val p = unparen(extendsType)
        val shape = (p as? TypeOperator)?.takeIf { it.operator == SyntaxKind.ReadonlyKeyword }?.let { unparen(it.type) } ?: p
        // (P18.307) a BARE `infer R` (optionally `infer R extends C`) is the whole pattern: tsgo infers `R` as the
        // check type itself (type-fest's `ApplyDefaultOptions`); [bind] decides it.
        if (p is InferType) return true
        return (shape is TupleType || shape is TemplateLiteralType || arrayPatternElement(shape) != null ||
            collectionPatternName(shape) != null) && containsInfer(shape)
    }

    /** The element node of an array pattern — `P[]`, or `Array<P>` / `ReadonlyArray<P>` — else null. */
    private fun arrayPatternElement(p: TypeNode): TypeNode? = when (p) {
        is ArrayType -> p.elementType
        is TypeReference -> p.typeArguments?.singleOrNull()?.takeIf {
            (p.typeName as? Identifier)?.text.let { n -> n == "Array" || n == "ReadonlyArray" }
        }
        else -> null
    }

    fun match(source: Type, pattern: TypeNode): Map<String, Type>? {
        val bindings = HashMap<String, Type>()
        return when (matchInto(source, pattern, bindings)) {
            Verdict.MATCH -> bindings
            Verdict.NO -> NO_MATCH
            Verdict.UNKNOWN -> null
        }
    }

    private enum class Verdict { MATCH, NO, UNKNOWN }

    private fun matchInto(source: Type, patternNode: TypeNode, bindings: HashMap<String, Type>): Verdict {
        val pattern = unparen(patternNode)
        if (pattern is InferType) return bind(source, pattern, bindings)
        if (!containsInfer(pattern)) {
            val p = checker.typeOfNodeBinding(pattern, bindings)
            if (p === errorType) return Verdict.UNKNOWN
            return if (checker.relateForInfer(source, p)) Verdict.MATCH else Verdict.NO
        }
        if (pattern is TypeOperator && pattern.operator == SyntaxKind.ReadonlyKeyword) {
            val inner = unparen(pattern.type)
            if (inner is TupleType) return matchTuple(source, inner, readonlyPattern = true, bindings)
            arrayPatternElement(inner)?.let { return matchArray(source, it, readonlyPattern = true, bindings) }
            return Verdict.UNKNOWN
        }
        if (pattern is TupleType) return matchTuple(source, pattern, readonlyPattern = false, bindings)
        if (pattern is TemplateLiteralType) {
            val m = checker.templateTypes.matchInferPattern(source, pattern,
                spanType = { if (containsInfer(it)) errorType else checker.typeOfNodeBinding(it, bindings) },
                constraintOf = { inf -> inf.typeParameter.constraint?.let { checker.typeOfNodeBinding(it, bindings) } },
                compare = checker::relateForInfer,
            ) ?: return Verdict.UNKNOWN
            if (m === TemplateLiteralTypes.NO_MATCH) return Verdict.NO
            for ((k, v) in m) { if (k in bindings) return Verdict.UNKNOWN; bindings[k] = v }
            return Verdict.MATCH
        }
        collectionPatternName(pattern)?.let { return matchCollection(source, pattern as TypeReference, it, bindings) }
        arrayPatternElement(pattern)?.let { el ->
            val readonly = (pattern as? TypeReference)?.typeName.let { (it as? Identifier)?.text == "ReadonlyArray" }
            return matchArray(source, el, readonly, bindings)
        }
        return Verdict.UNKNOWN
    }

    /** `Set` / `Map` / `ReadonlySet` / `ReadonlyMap` — a pattern matched positionally against the lib pair. */
    private fun collectionPatternName(p: TypeNode): String? =
        ((p as? TypeReference)?.typeName as? Identifier)?.text?.takeIf { it in COLLECTIONS }

    /** tsgo infers `Set<string> extends ReadonlySet<infer I>` through the members; the lib pair's
     *  type parameters correspond one to one, so the arguments are matched by position. */
    private fun matchCollection(source: Type, pattern: TypeReference, name: String, bindings: HashMap<String, Type>): Verdict {
        val ref = collectionView(source) ?: return if (isNeverTupleLike(source)) Verdict.NO else Verdict.UNKNOWN
        val srcName = ref.target.symbol?.name
        val ok = srcName == name || name == "Readonly$srcName" && srcName in COLLECTIONS
        if (!ok) return if (srcName in COLLECTIONS || checker.isArrayLikeReference(ref)) Verdict.NO else Verdict.UNKNOWN
        val args = ref.resolvedTypeArguments ?: return Verdict.UNKNOWN
        val pArgs = pattern.typeArguments ?: return Verdict.UNKNOWN
        if (args.size != pArgs.size) return Verdict.UNKNOWN
        for (i in args.indices) {
            val v = matchInto(args[i], pArgs[i], bindings)
            if (v != Verdict.MATCH) return v
        }
        return Verdict.MATCH
    }

    /** [source] as a collection reference: itself, `{} & X` (type-fest's `ReadonlySetDeep`), or the
     *  `Readonly<X>` of one — a homomorphic mapping that keeps `X`'s members (read-only already). */
    private fun collectionView(source: Type): Type.Reference? {
        var t = source
        if (t is Type.Intersection) t = t.types.filterNot { checker.isEmptyObjectLiteralType(it) }.singleOrNull() ?: return null
        if (t is Type.Reference) return t
        val (alias, args) = checker.aliasDisplayMap[t.id] ?: return null
        val arg = args.singleOrNull() as? Type.Reference ?: return null
        return arg.takeIf { alias == "Readonly" && it.target.symbol?.name in setOf("ReadonlySet", "ReadonlyMap") }
    }

    /** An array pattern: an array source's element, or a tuple's element union (tsgo infers through the
     *  tuple's `Array` base); a readonly source never matches a mutable pattern. */
    private fun matchArray(source: Type, element: TypeNode, readonlyPattern: Boolean, bindings: HashMap<String, Type>): Verdict {
        val base: Type.Reference = when {
            checker.isArrayLikeReference(source) -> source as Type.Reference
            source is Type.Object && source.tupleElementTypes != null -> checker.tupleArrayBaseOf(source) ?: return Verdict.UNKNOWN
            else -> return if (isNeverTupleLike(source)) Verdict.NO else Verdict.UNKNOWN
        }
        if (base.target.symbol?.name == "ReadonlyArray" && !readonlyPattern) return Verdict.NO
        val el = base.resolvedTypeArguments?.singleOrNull() ?: return Verdict.UNKNOWN
        return matchInto(el, element, bindings)
    }

    /** A primitive or `unknown`: never an array or a tuple. */
    private fun isNeverTupleLike(source: Type): Boolean =
        source === unknownType || source is Type.Intrinsic && source !== anyType && source !== errorType &&
            source !== neverType || source is Type.StringLiteral || source is Type.NumberLiteral ||
            source is Type.BigIntLiteral

    private fun bind(source: Type, infer: InferType, bindings: HashMap<String, Type>): Verdict {
        val name = infer.typeParameter.name.text
        if (name in bindings) return Verdict.UNKNOWN // tsgo unions the candidates; not modelled
        infer.typeParameter.constraint?.let { c ->
            val ct = checker.typeOfNodeBinding(c, bindings)
            if (ct === errorType) return Verdict.UNKNOWN
            if (ct !== anyType && ct !== unknownType && !checker.relateForInfer(source, ct)) return Verdict.NO
        }
        bindings[name] = source
        return Verdict.MATCH
    }

    private class Slot(val node: TypeNode, val kind: Int)

    private fun slotsOf(t: TupleType): List<Slot> = t.elements.mapIndexed { i, e ->
        val optional = t.elementOptional?.getOrNull(i) == true
        when (e) {
            is RestType -> Slot(e.type, REST)
            is OptionalType -> Slot(e.type, OPTIONAL)
            is NamedTupleMember -> Slot(e.type, if (e.dotDotDotToken) REST else if (e.questionToken || optional) OPTIONAL else REQUIRED)
            else -> Slot(e, if (optional) OPTIONAL else REQUIRED)
        }
    }

    private fun matchTuple(source: Type, pattern: TupleType, readonlyPattern: Boolean, bindings: HashMap<String, Type>): Verdict {
        val slots = slotsOf(pattern)
        val restAt = slots.indexOfFirst { it.kind == REST }
        if (slots.count { it.kind == REST } > 1) return Verdict.UNKNOWN
        val src = source as? Type.Object
        val elems = src?.tupleElementTypes
        if (src == null || elems == null) return matchNonTuple(source, slots, restAt, readonlyPattern, bindings)
        if (src.readonlyTuple && !readonlyPattern) return Verdict.NO
        val n = elems.size
        val srcRest = src.tupleRestIndex
        if (srcRest >= 0 && elems[srcRest] !is Type.Reference) return Verdict.UNKNOWN // a generic rest
        if (restAt < 0) {
            // A fixed pattern: required slots first, then optional ones; no source rest.
            val firstOpt = slots.indexOfFirst { it.kind == OPTIONAL }.let { if (it < 0) slots.size else it }
            if (slots.drop(firstOpt).any { it.kind != OPTIONAL }) return Verdict.UNKNOWN
            if (srcRest >= 0) return Verdict.NO
            if (n > slots.size) return Verdict.NO
            val srcRequired = (0 until n).count { !checker.tupleSlotIsOptional(src, it) }
            if (srcRequired < firstOpt) return Verdict.NO
            for (i in 0 until n) {
                if (checker.tupleSlotIsOptional(src, i) && slots[i].kind != OPTIONAL) return Verdict.NO
                val v = matchInto(elems[i], slots[i].node, bindings)
                if (v != Verdict.MATCH) return v
            }
            // tsgo: an optional `infer` slot the source does not provide infers nothing — `unknown`.
            for (i in n until slots.size) {
                val v = matchAbsent(slots[i].node, bindings)
                if (v != Verdict.MATCH) return v
            }
            return Verdict.MATCH
        }
        if (slots.any { it.kind == OPTIONAL }) return matchOptionalPrefix(src, elems, slots, restAt, bindings)
        val prefix = restAt
        val suffix = slots.size - restAt - 1
        val srcFixedPrefix = if (srcRest >= 0) srcRest else n
        val srcFixedSuffix = if (srcRest >= 0) n - srcRest - 1 else n
        if (srcRest >= 0) {
            if (srcFixedPrefix < prefix || srcFixedSuffix < suffix) return Verdict.NO
        } else if (n < prefix + suffix) return Verdict.NO
        for (i in 0 until prefix) {
            if (checker.tupleSlotIsOptional(src, i)) return Verdict.NO
            val v = matchInto(elems[i], slots[i].node, bindings)
            if (v != Verdict.MATCH) return v
        }
        for (j in 0 until suffix) {
            val si = n - suffix + j
            if (checker.tupleSlotIsOptional(src, si)) return Verdict.NO
            val v = matchInto(elems[si], slots[restAt + 1 + j].node, bindings)
            if (v != Verdict.MATCH) return v
        }
        // The variadic slice (tsgo `sliceTupleType`): a lone source rest is its own array type;
        // anything else is a fresh MUTABLE tuple carrying optionality and labels.
        val from = prefix
        val to = n - suffix
        val slice: Type = if (srcRest >= 0 && from == srcRest && to == srcRest + 1) {
            val restArray = elems[srcRest] as Type.Reference
            if (src.readonlyTuple) mutableArrayOf(restArray) ?: return Verdict.UNKNOWN else restArray
        } else {
            val types = elems.subList(from, to)
            val opt = (from until to).map { checker.tupleSlotIsOptional(src, it) }.takeIf { fl -> fl.any { it } }
            val names = src.tupleElementNames?.subList(from, to)
            val rest = if (srcRest >= 0) srcRest - from else -1
            checker.buildTupleFromTypes(types, opt, readonly = false, restIndex = rest, names = names)
        }
        return matchInto(slice, slots[restAt].node, bindings)
    }

    /**
     * (P18.302) A pattern of OPTIONAL slots followed by a trailing rest — type-fest's
     * `[(infer First)?, ...infer Rest]`. Each optional slot takes the source's element at its
     * position (an element the source's own rest provides is the rest's element type), the rest
     * slot takes what remains, as tsgo infers it (`[]` -> `[unknown, unknown[]]`).
     */
    private fun matchOptionalPrefix(
        src: Type.Object, elems: List<Type>, slots: List<Slot>, restAt: Int, bindings: HashMap<String, Type>,
    ): Verdict {
        if (restAt != slots.size - 1 || (0 until restAt).any { slots[it].kind != OPTIONAL }) return Verdict.UNKNOWN
        val n = elems.size
        val srcRest = src.tupleRestIndex
        val fixed = if (srcRest >= 0) srcRest else n
        if (fixed < restAt && (srcRest != n - 1 || srcRest < 0)) {
            // tsgo infers nothing past what the source supplies: an absent slot is `unknown`, the
            // rest `unknown[]`; a non-trailing source rest supplies nothing past the fixed prefix.
            val supplied = fixed
            for (i in 0 until restAt) {
                val v = if (i < supplied) matchInto(elems[i], slots[i].node, bindings) else matchAbsent(slots[i].node, bindings)
                if (v != Verdict.MATCH) return v
            }
            val r = unparen(slots[restAt].node)
            if (r !is InferType || r.typeParameter.constraint != null || r.typeParameter.name.text in bindings) return Verdict.UNKNOWN
            bindings[r.typeParameter.name.text] = checker.getOrInternReference(checker.globalArrayType, listOf(unknownType))
            return Verdict.MATCH
        }
        val restArray = if (srcRest >= 0) elems[srcRest] as? Type.Reference ?: return Verdict.UNKNOWN else null
        for (i in 0 until restAt) {
            val el = if (i < fixed) elems[i] else restArray?.resolvedTypeArguments?.singleOrNull() ?: return Verdict.UNKNOWN
            val v = matchInto(el, slots[i].node, bindings)
            if (v != Verdict.MATCH) return v
        }
        val slice: Type = if (restArray != null && restAt >= srcRest && srcRest == n - 1) {
            if (src.readonlyTuple) mutableArrayOf(restArray) ?: return Verdict.UNKNOWN else restArray
        } else {
            val types = elems.subList(restAt, n)
            val opt = (restAt until n).map { checker.tupleSlotIsOptional(src, it) }.takeIf { fl -> fl.any { it } }
            val names = src.tupleElementNames?.subList(restAt, n)
            val rest = if (srcRest >= 0) srcRest - restAt else -1
            checker.buildTupleFromTypes(types, opt, readonly = false, restIndex = rest, names = names)
        }
        return matchInto(slice, slots[restAt].node, bindings)
    }

    /** An `Array<T>` / `ReadonlyArray<T>` source: only a lone rest pattern `[...X]` can match it. */
    private fun matchNonTuple(
        source: Type, slots: List<Slot>, restAt: Int, readonlyPattern: Boolean, bindings: HashMap<String, Type>,
    ): Verdict {
        if (checker.isArrayLikeReference(source)) {
            val ref = source as Type.Reference
            val readonlySource = ref.target.symbol?.name == "ReadonlyArray"
            if (slots.any { it.kind == REQUIRED }) return Verdict.NO
            // (P18.302) leading OPTIONAL slots before the rest each take the element type.
            if (restAt != slots.size - 1 || (0 until restAt).any { slots[it].kind != OPTIONAL }) return Verdict.UNKNOWN
            if (readonlySource && !readonlyPattern) return Verdict.NO
            val arr = if (readonlySource) mutableArrayOf(ref) ?: return Verdict.UNKNOWN else ref
            if (restAt > 0) {
                val el = ref.resolvedTypeArguments?.singleOrNull() ?: return Verdict.UNKNOWN
                for (i in 0 until restAt) {
                    val v = matchInto(el, slots[i].node, bindings)
                    if (v != Verdict.MATCH) return v
                }
            }
            return matchInto(arr, slots[restAt].node, bindings)
        }
        // A primitive or `unknown` is never a tuple; anything else (generic, `any`, object …) is not decided.
        return if (isNeverTupleLike(source)) Verdict.NO else Verdict.UNKNOWN
    }

    /** An optional pattern slot with no source element: an `infer` there binds `unknown`. */
    private fun matchAbsent(node: TypeNode, bindings: HashMap<String, Type>): Verdict {
        val p = unparen(node)
        if (p is InferType && p.typeParameter.constraint == null && p.typeParameter.name.text !in bindings) {
            bindings[p.typeParameter.name.text] = unknownType
            return Verdict.MATCH
        }
        return if (containsInfer(p)) Verdict.UNKNOWN else Verdict.MATCH
    }

    private fun mutableArrayOf(ref: Type.Reference): Type? {
        val elem = ref.resolvedTypeArguments?.singleOrNull() ?: return null
        return checker.getOrInternReference(checker.globalArrayType, listOf(elem))
    }

    private fun unparen(n: TypeNode): TypeNode {
        var t = n
        while (t is ParenthesizedType) t = t.type
        return t
    }

    private fun containsInfer(n: Node): Boolean {
        if (n is InferType) return true
        var found = false
        forEachChild(n) { if (!found && containsInfer(it)) found = true }
        return found
    }

    companion object {
        private const val REQUIRED = 0
        private const val OPTIONAL = 1
        private const val REST = 2
        private val COLLECTIONS = setOf("Set", "Map", "ReadonlySet", "ReadonlyMap")

        /** [match]'s "definitely not related" answer (compared by identity). */
        val NO_MATCH: Map<String, Type> = HashMap()
    }
}
