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
 */



package com.xemantic.typescript.compiler

/**
 * (CHK.184) tsgo's `narrowTypeByDiscriminant` (`internal/checker/flow.go`) for the one family
 * this checker was missing: a union narrowed by a condition on a DISCRIMINANT PROPERTY whose
 * value set contains `undefined` — `{ a: T; b?: undefined } | { b: T; a?: undefined }`
 * narrowed by `w.a` (truthiness), `w.a === undefined` / `!== undefined`, or
 * `typeof w.a === "undefined"`.
 *
 * tsgo narrows the PROPERTY's type across the union (`narrowedPropType`) with the ordinary
 * operator-specific narrowing, then keeps each constituent whose own discriminant type is
 * COMPARABLE with it. The input this checker lacked is optionality: an optional member's
 * read type is `T | undefined` (in tsgo, `getTypeOfSymbol` adds `undefined` / `missing`),
 * while here optionality is a SYMBOL attribute not folded into the resolved property type —
 * the round-425 reason the equality filter refuses a nullish tested literal outright. The
 * fold happens here, so `x.body === undefined` over a member with `body?: F` KEEPS it.
 *
 * Gates, each of which is what keeps it sound:
 *  - strictNullChecks (without it `undefined` is in every type and nothing is comparable-
 *    disjoint);
 *  - [t] is a union and the property is a discriminant of its object constituents
 *    (tsgo `isDiscriminantProperty`: non-uniform and at least one literal/unit type, over the
 *    FOLDED types; a generic member type refuses);
 *  - comparability is decided DISJOINT only where it is certain: a nullish type against a
 *    non-nullish one, or two unequal unit literals. Every other pair is kept.
 *
 * Answers null — "not applicable, fall through" — when a gate refuses, when the narrowed
 * property type is `never`, or when the filter removes nothing or everything.
 */
internal class DiscriminantFactsNarrowing(private val checker: Checker) {

    fun narrow(t: Type, propName: String, narrowProp: (Type) -> Type): Type? {
        if (!checker.strictNullChecks || t !is Type.Union || propName.isEmpty()) return null
        val discs = arrayOfNulls<Type>(t.types.size)
        var first: Type? = null
        var nonUniform = false
        var hasLiteral = false
        val present = ArrayList<Type>(t.types.size)
        for ((i, m) in t.types.withIndex()) {
            val obj = checker.getApparentType(m) as? Type.Object ?: continue
            val sym = checker.getPropertyOfType(obj, propName) ?: continue
            var pt = checker.getPropertyTypeForRelation(obj, sym)
            if (pt === anyType || pt === errorType || pt === unknownType) return null
            if (checker.typeContainsUnresolvedTypeParam(pt)) return null
            if (checker.isOptionalProperty(sym) && !includesUndefined(pt)) {
                pt = if (pt === neverType) undefinedType else checker.getUnionType(listOf(pt, undefinedType))
            }
            discs[i] = pt
            present.add(pt)
            val f = first
            if (f == null) first = pt else if (!checker.sameTypeForDiscriminant(f, pt)) nonUniform = true
            if (!hasLiteral && checker.isLiteralTypeForDiscriminant(pt)) hasLiteral = true
        }
        if (!nonUniform || !hasLiteral) return null
        val narrowed = narrowProp(checker.getUnionType(present))
        if (narrowed === neverType) return null
        val n = constituents(narrowed)
        val kept = t.types.filterIndexed { i, _ ->
            val d = discs[i] ?: return@filterIndexed true
            val dc = constituents(d)
            n.any { a -> dc.any { b -> comparable(a, b) } }
        }
        if (kept.isEmpty() || kept.size == t.types.size) return null
        return if (kept.size == 1) kept[0] else checker.getUnionType(kept)
    }

    private fun constituents(t: Type): List<Type> =
        (if (t is Type.Union) t.types else listOf(t)).filter { it !== neverType }

    private fun includesUndefined(t: Type): Boolean =
        t.flags.hasAny(TypeFlags.Undefined) || (t is Type.Union && t.types.any { it.flags.hasAny(TypeFlags.Undefined) })

    private fun isNullish(t: Type): Boolean = t.flags.hasAny(NULLISH)

    /** Disjoint only where certain; every undecided pair is comparable. */
    private fun comparable(a: Type, b: Type): Boolean {
        if (a === b) return true
        val an = isNullish(a)
        val bn = isNullish(b)
        if (an || bn) return an && bn && a.flags.hasAny(TypeFlags.Null) == b.flags.hasAny(TypeFlags.Null)
        if (checker.isLiteralKindForDiscriminant(a) && checker.isLiteralKindForDiscriminant(b)) {
            return checker.literalsEqualForDiscriminant(a, b)
        }
        return true
    }

    companion object {
        private val NULLISH = TypeFlags.Null or TypeFlags.Undefined or TypeFlags.Void

        /** The `===` / `!==` (strict) or `==` / `!=` (loose) narrowing of a property type by a
         *  nullish keyword: keep the matching nullish constituents when [equal], else drop them. */
        fun nullishEquality(t: Type, isNull: Boolean, loose: Boolean, equal: Boolean, checker: Checker): Type {
            val mask = when {
                loose -> NULLISH
                isNull -> TypeFlags.Null
                else -> TypeFlags.Undefined or TypeFlags.Void
            }
            val parts = if (t is Type.Union) t.types else listOf(t)
            val kept = parts.filter { it.flags.hasAny(mask) == equal }
            return when (kept.size) {
                0 -> neverType
                parts.size -> t
                1 -> kept[0]
                else -> checker.getUnionType(kept)
            }
        }
    }
}
