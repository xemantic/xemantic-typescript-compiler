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
 * (INC.11) THE CLASSIFIER for the residual capture divergence, and it is
 * deliberately a WITHIN-ARM instrument.
 *
 * The question (INC.10) left open is whether the 462 spans that survive a
 * `TypeAlias`-eager deferral are ONE interned type rendered under two different
 * alias names, or TWO genuinely different `Type` instances (a conditional type
 * evaluated in one arm and not the other). A cross-arm comparison cannot answer it:
 * `Type.id` is minted in resolution order, so "the same id" is not a claim two arms
 * can even make about each other.
 *
 * Within ONE arm it is provable by construction. `aliasDisplayMap` has two writers
 * and they have OPPOSITE policies:
 *
 *  * the GENERIC-instantiation site writes `aliasDisplayMap[result.id] = name to
 *    args` unconditionally — **last wins**;
 *  * the non-generic `TypeAlias` site writes only `if (!containsKey(resolved.id))`
 *    — **first wins**.
 *
 * So if a whole-program build is observed CLOBBERING an existing `A` registration
 * with a `B<…>` one at the same id, then `A` and `B<…>` are two names for the SAME
 * `Type` instance, and a build that never performs the `B<…>` instantiation renders
 * the same type as `A`. That is a display divergence, proven inside one process,
 * with no id crossing an arm boundary.
 *
 * Off by default; every hook is a static boolean read and a not-taken branch.
 * Armed by `XTSC_ALIAS_CENSUS=1` in the two capture sweeps.
 */
object AliasDisplayCensus {

    /** Master switch. */
    var on: Boolean = false

    /** Writes at the generic-instantiation (last-wins) site. */
    var genericWrites: Long = 0

    /** …of which landed on an id that had no entry. */
    var genericFresh: Long = 0

    /** …of which overwrote an entry carrying the SAME rendered name. */
    var genericSameName: Long = 0

    /** …of which overwrote an entry carrying a DIFFERENT name — the finding. */
    var genericClobbers: Long = 0

    /** Writes at the non-generic (first-wins) site. */
    var plainWrites: Long = 0

    /** Asks at the non-generic site refused because an entry already existed. */
    var plainRefused: Long = 0

    /** `"<old> <- <new>"` rows for the clobbers, capped so a sweep stays printable. */
    val clobberRows: MutableList<String> = ArrayList()

    /** …of the refusals, the ones where the standing name DIFFERS from the refused one. */
    var plainRefusedDifferent: Long = 0

    /** `"<standing> beats <refused>"` rows for those. */
    val refusalRows: MutableList<String> = ArrayList()

    /** Generic instantiations that returned one of their own arguments unchanged. */
    var genericArgIdentity: Long = 0

    /** The alias names that did so. */
    val argIdentityRows: MutableList<String> = ArrayList()

    /** (INC.27) B416 union-alias member sets registered under exactly one name. */
    var unionRegistered: Long = 0

    /** …and the ones POISONED because a second, differently-named alias claimed them. */
    var unionAmbiguous: Long = 0

    /** `"<standing> vs <incoming>"` rows for the poisoned member sets. */
    val unionAmbiguousRows: MutableList<String> = ArrayList()

    /** The alias names that registered a member set. */
    val unionRegisteredRows: MutableList<String> = ArrayList()

    private const val ROW_CAP = 4000

    fun reset() {
        genericWrites = 0
        genericFresh = 0
        genericSameName = 0
        genericClobbers = 0
        plainWrites = 0
        plainRefused = 0
        plainRefusedDifferent = 0
        genericArgIdentity = 0
        unionRegistered = 0
        unionAmbiguous = 0
        unionAmbiguousRows.clear()
        unionRegisteredRows.clear()
        clobberRows.clear()
        refusalRows.clear()
        argIdentityRows.clear()
    }

    /** Hook for an instantiation that answered one of its own arguments. */
    fun noteArgIdentity(name: String) {
        genericArgIdentity++
        if (argIdentityRows.size < ROW_CAP) argIdentityRows.add(name)
    }

    /** Hook for the generic (last-wins) site, called BEFORE the write. */
    fun noteGeneric(existing: String?, incoming: String) {
        genericWrites++
        when {
            existing == null -> genericFresh++
            existing == incoming -> genericSameName++
            else -> {
                genericClobbers++
                if (clobberRows.size < ROW_CAP) clobberRows.add("$existing <- $incoming")
            }
        }
    }

    /**
     * Hook for the non-generic (first-wins) site.
     *
     * A REFUSAL whose standing name differs from the refused one is the finding this
     * object exists for: the two names are then two names for the SAME `Type`, so a
     * build that resolves them in the other order renders the other name.
     */
    fun notePlain(refused: Boolean, standing: String?, incoming: String) {
        if (!refused) {
            plainWrites++
            return
        }
        plainRefused++
        if (standing != null && standing != incoming) {
            plainRefusedDifferent++
            if (refusalRows.size < ROW_CAP) refusalRows.add("$standing beats $incoming")
        }
    }

    /** (INC.27) Hook for a B416 registration that took a previously-unclaimed member set. */
    fun noteUnionRegistered(name: String) {
        unionRegistered++
        if (unionRegisteredRows.size < ROW_CAP) unionRegisteredRows.add(name)
    }

    /** (INC.27) Hook for a B416 member set poisoned by a second, differently-named alias. */
    fun noteUnionAmbiguous(standing: String, incoming: String) {
        unionAmbiguous++
        if (unionAmbiguousRows.size < ROW_CAP) unionAmbiguousRows.add("$standing vs $incoming")
    }

    fun report(): String = buildString {
        appendLine("== (INC.11) aliasDisplayMap write census ==")
        appendLine("generic (last-wins) writes: $genericWrites")
        appendLine("  fresh id            : $genericFresh")
        appendLine("  same name overwrite : $genericSameName")
        appendLine("  DIFFERENT-NAME CLOBBER: $genericClobbers")
        appendLine(
            "plain (first-wins) writes: $plainWrites  refused: $plainRefused " +
                "of which DIFFERENT-NAME: $plainRefusedDifferent",
        )
        val refusals = refusalRows.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }
        appendLine("distinct different-name refusal rows: ${refusals.size}")
        for ((row, n) in refusals.take(60)) appendLine("  ${n}x  $row")
        appendLine("generic instantiations answering an ARGUMENT unchanged: $genericArgIdentity")
        val identities = argIdentityRows.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }
        for ((row, n) in identities.take(30)) appendLine("  ${n}x  $row")
        val distinct = clobberRows.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }
        appendLine("distinct clobber rows: ${distinct.size}")
        for ((row, n) in distinct.take(60)) appendLine("  ${n}x  $row")
        appendLine(
            "(INC.27) B416 union-alias member sets: registered=$unionRegistered " +
                "POISONED=$unionAmbiguous",
        )
        val poisoned = unionAmbiguousRows.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }
        for ((row, n) in poisoned.take(40)) appendLine("  ${n}x  $row")
    }
}
