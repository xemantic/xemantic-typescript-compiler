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
 * (INV.0) Canonical type identity — the ONE home of [Type.Reference],
 * [Type.Union] and [Type.Intersection] interning, extracted from `Checker.kt`
 * as Stage 0's first collaborator (`docs/INVERSION-DESIGN.md` § 6; the ambient
 * ledger row is `docs/inversion-ambient-ledger.md` § 1).
 *
 * Identical instantiations share an instance and therefore a `Type.id`, which
 * is what the id-based cycle detection in `relationComparisonStack` relies on
 * (`interface List<T> { next: List<T> }` compares as ONE pair), what INV.5(a)'s
 * union identity means, and what makes a packed `(id, id)` relation key sound.
 *
 * ## Contract (docs/INVERSION-DESIGN.md § 10)
 *
 * Final class, constructed ONCE per `Checker` (it lives in `CheckerState`,
 * whose lifetime it shares — the caches must die with the checker, because
 * `Type.id`s are a per-checker, per-thread sequence, INV.6(6c0)); every input
 * travels as a parameter; it reads and writes NO checker ambient — the six
 * maps below are its own.
 *
 * ## Key shapes (M0.3(iii), moved verbatim)
 *
 * The dominant shapes — 0/empty/1-arg references, 2-member unions and
 * intersections — intern via an exact packed-Long key instead of building a
 * string key per consult (the round-618 JFR hotspot). Larger shapes stay on
 * the string maps. A packed key is a BIJECTION of the id tuple, never a hash;
 * ids are positive (< 2^31) and a reference's high half is `target.id >= 1`,
 * so no packed key collides with [LongKeyMap]'s `0L` sentinel.
 */
internal class TypeInterner {

    /** Interning cache for [Type.Reference], keyed on `target.id|arg1.id,arg2.id,...`. */
    private val referenceCache = HashMap<String, Type.Reference>()
    private val unionCache = HashMap<String, Type.Union>()
    private val intersectionCache = HashMap<String, Type.Intersection>()
    private val referenceCacheLong = LongKeyMap<Type.Reference>()
    private val unionCacheLong = LongKeyMap<Type.Union>()
    private val intersectionCacheLong = LongKeyMap<Type.Intersection>()

    /**
     * The instance for `target` instantiated at `args` — do NOT call
     * `Type.Reference(...)` directly in checker code (CLAUDE.md invariant).
     *
     * `null` args and an EMPTY list both pack `low = 0`, deliberately
     * REPRODUCING the historical string key's conflation (both built `"id|"` —
     * the first toucher's instance wins for the other shape).
     */
    fun reference(target: Type.Interface, args: List<Type>?): Type.Reference {
        if (args == null || args.size <= 1) {
            val low = if (args.isNullOrEmpty()) 0L else args[0].id.toLong() and 0xFFFFFFFFL
            val key = (target.id.toLong() shl 32) or low
            return referenceCacheLong.get(key)
                ?: Type.Reference(target, resolvedTypeArguments = args)
                    .also { referenceCacheLong.put(key, it) }
        }
        val key = buildString {
            append(target.id)
            append('|')
            args.joinTo(this, ",") { it.id.toString() }
        }
        return referenceCache.getOrPut(key) {
            Type.Reference(target, resolvedTypeArguments = args)
        }
    }

    /**
     * The instance for an already-normalized union member list — flattening,
     * `never` removal, dedup and the flags-value sort are the CALLER's
     * (`getUnionType`'s) job; this is identity only. The dominant 2-member
     * union (`T | undefined`) interns via the exact packed-Long key.
     */
    fun union(members: List<Type>): Type.Union {
        if (members.size == 2) {
            val key = (members[0].id.toLong() shl 32) or (members[1].id.toLong() and 0xFFFFFFFFL)
            return unionCacheLong.get(key)
                ?: Type.Union(members).also { unionCacheLong.put(key, it) }
        }
        val key = members.joinToString(",") { it.id.toString() }
        return unionCache.getOrPut(key) { Type.Union(members) }
    }

    /** The instance for an already-normalized intersection member list —
     *  same division of labour as [union], `getIntersectionType` normalizes. */
    fun intersection(members: List<Type>): Type.Intersection {
        if (members.size == 2) {
            val key = (members[0].id.toLong() shl 32) or (members[1].id.toLong() and 0xFFFFFFFFL)
            return intersectionCacheLong.get(key)
                ?: Type.Intersection(members).also { intersectionCacheLong.put(key, it) }
        }
        val key = members.joinToString(",") { it.id.toString() }
        return intersectionCache.getOrPut(key) { Type.Intersection(members) }
    }

}
