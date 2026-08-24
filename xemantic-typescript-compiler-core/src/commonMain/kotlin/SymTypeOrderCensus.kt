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
 * (INC.23) THE CENSUS FOR "WHY DOES RESOLVING A MEMBER DEPEND ON WHO ASKED FIRST".
 *
 * (INC.22) measured that partition-scoping `init:buildFileLocalTypeMaps` collapses
 * **+61 captured member types to `any`** (`capture-channel-equivalence`'s
 * `narrowRendersMoreAny` 168 -> 229) while moving no diagnostic and no emitted byte.
 * That is a wrong answer, not a missing one, and the queue item that followed says
 * to CENSUS the 61 rather than design against them: *which symbol, resolved by which
 * pass, under what ambient instantiation context, in each arm.*
 *
 * The known mechanism to confirm or refute is round 778's write gate:
 * `getTypeOfSymbol` persists into `symbolTypes` only when the CALLER's ambient
 * instantiation context is empty, so a member first resolved from inside a namespace
 * body, a type-parameter scope or an alias-arg install answers CORRECTLY and is NOT
 * RECORDED. (INC.6) found the mirror case one layer over.
 *
 * ## What it records, and why in two tables
 *
 * [firstResolve] is the WRITER's ledger: the first resolution of each symbol that
 * reached the worker, with the pass that drove it and the three ambient dimensions
 * `symbolTypeContextIsEmpty` reads. [memberRows] is the READER's: every member a
 * capture rendered, with its own resolution's provenance attached. Neither alone
 * answers the question — the ledger says who won the race and the rows say which
 * races a user can see.
 *
 * ## It is OFF and behaviour-free
 *
 * Armed by `XTSC_SYMORDER=1` from the sweep runners only. Every hook is inside an
 * `if (on)` BLOCK and not merely guarded by one, because Kotlin evaluates arguments
 * strictly and a census whose argument does the work is round 900's defect.
 */
object SymTypeOrderCensus {

    /** Armed by the sweeps; false is the whole compiler. */
    var on: Boolean = false

    /**
     * The substring a rendered member row must contain to be REPORTED. `any` by
     * default, because the divergence class (INC.23) exists to explain is a member
     * type collapsing to `any` — but the arm that RENDERS it correctly prints no
     * such row, so comparing the two arms' provenance needs the filter widened to
     * the member's own name.
     */
    var rowFilter: String = "any"

    /**
     * `symbolId` -> the FIRST resolution that reached `getTypeOfSymbolWorker`.
     *
     * Keyed by id and not by name because the question is about one `Symbol`
     * INSTANCE's frozen verdict; the name is carried in the value for reading.
     */
    val firstResolve: MutableMap<Int, String> = HashMap()

    /** `symbolId` -> how many times a resolution reached the worker. */
    val resolves: MutableMap<Int, Int> = HashMap()

    /** One row per rendered completion member, in render order. */
    val memberRows: MutableList<String> = ArrayList()

    /**
     * (INC.28) THE SECOND WRITER LEDGER — `Checker.declaredTypes`, which is a
     * DIFFERENT cache from `symbolTypes` with a DIFFERENT staleness rule.
     *
     * `getTypeOfSymbol` persists behind round 778's write gate
     * (`symbolTypeContextIsEmpty`); `getDeclaredTypeOfSymbol` persists
     * UNCONDITIONALLY, so the first resolution of a type alias's declared type
     * freezes whatever ambient instantiation context happened to be installed at
     * that moment. This records that first write per symbol so the two arms of a
     * full-vs-narrow sweep can be compared on WHO wrote and under WHAT ambient.
     *
     * Kept behind [declaredMatches] as well as [on]: the value is a `typeToString`
     * of the resolved type, which is real work and must never be computed for a
     * symbol the investigation is not about (round 900 — a guard inside the callee
     * cannot protect its own argument).
     */
    val declaredWrites: MutableMap<Int, String> = HashMap()

    /** How many `declaredTypes` writes the filter admitted, in order. */
    val declaredOrder: MutableList<String> = ArrayList()

    /**
     * Comma-separated symbol names whose `declaredTypes` write is recorded. EMPTY
     * means record NOTHING — a census that records everything would `typeToString`
     * every declared type in the program.
     */
    var declaredFilter: String = ""

    /** Is [name] one of [declaredFilter]'s names? Cheap, and false when unset. */
    fun declaredMatches(name: String): Boolean {
        if (declaredFilter.isEmpty()) return false
        var from = 0
        while (from <= declaredFilter.length) {
            val next = declaredFilter.indexOf(',', from).let { if (it < 0) declaredFilter.length else it }
            if (next - from == name.length && declaredFilter.regionMatches(from, name, 0, name.length)) return true
            from = next + 1
        }
        return false
    }

    fun reset() {
        firstResolve.clear()
        resolves.clear()
        memberRows.clear()
        declaredWrites.clear()
        declaredOrder.clear()
    }

    /**
     * The `declaredTypes` WRITER hook. [ambientOf]'s three dimensions plus the
     * resolution depth, exactly as [noteResolve] records them, so a row from either
     * ledger reads the same way.
     */
    fun noteDeclared(
        id: Int,
        name: String,
        pass: String?,
        typeParamScope: Boolean,
        typeAliasArgs: Boolean,
        inferenceNamespace: Boolean,
        typeText: String,
        symbolDepth: Int,
        nodeDepth: Int,
    ) {
        val row = "name=$name id=$id pass=${pass ?: "<outside>"} " +
            "ambient=${ambientOf(typeParamScope, typeAliasArgs, inferenceNamespace)} " +
            "depth=sym$symbolDepth/node$nodeDepth type=$typeText"
        declaredOrder.add(row)
        if (id in declaredWrites) return
        declaredWrites[id] = row
    }

    private fun ambientOf(
        typeParamScope: Boolean,
        typeAliasArgs: Boolean,
        inferenceNamespace: Boolean,
    ): String = buildString {
        if (typeParamScope) append("tps,")
        if (typeAliasArgs) append("alias,")
        if (inferenceNamespace) append("ns,")
        if (isEmpty()) append("empty")
    }.removeSuffix(",")

    /** Every admitted `declaredTypes` write, in write order, with the first flagged. */
    fun declaredReport(tag: String): String = buildString {
        appendLine("DECLARED[$tag] writes=${declaredOrder.size} symbols=${declaredWrites.size}")
        val firsts = declaredWrites.values.toSet()
        for (row in declaredOrder) {
            appendLine("DECLARED[$tag]  ${if (row in firsts) "FIRST" else "     "} $row")
        }
    }

    /**
     * The WRITER hook — called once per resolution that gets past the memo, with
     * the three dimensions of the round-778 gate spelled out separately so a
     * refusal can be attributed to the dimension that caused it.
     */
    fun noteResolve(
        id: Int,
        name: String,
        pass: String?,
        typeParamScope: Boolean,
        typeAliasArgs: Boolean,
        inferenceNamespace: Boolean,
        persisted: Boolean,
        symbolDepth: Int,
        nodeDepth: Int,
        memberDepth: Int,
        truncated: Boolean,
    ) {
        resolves[id] = (resolves[id] ?: 0) + 1
        if (id in firstResolve) return
        val ambient = buildString {
            if (typeParamScope) append("tps,")
            if (typeAliasArgs) append("alias,")
            if (inferenceNamespace) append("ns,")
            if (isEmpty()) append("empty")
        }.removeSuffix(",")
        // (INC.23) THE FOURTH DIMENSION, and the one round 778's gate does NOT read:
        // how deep inside OTHER resolutions this one is. `symbolTypeContextIsEmpty`
        // asks whether the ambient INSTANTIATION context is empty; it says nothing
        // about whether the answer was truncated by an in-progress sentinel, and a
        // truncated answer is `any`.
        firstResolve[id] =
            "name=$name pass=${pass ?: "<outside>"} ambient=$ambient persisted=$persisted " +
                "depth=sym$symbolDepth/node$nodeDepth/member$memberDepth truncated=$truncated"
    }

    /** The READER hook — one captured completion member and its provenance. */
    fun noteMember(
        name: String,
        typeText: String,
        id: Int,
        memoHit: Boolean,
        declKind: String,
        declFile: String,
        declPos: Int,
    ) {
        memberRows.add(
            "MEMBER $name : $typeText  sym=$id memo=$memoHit " +
                "decl=$declKind@${declFile.substringAfterLast('/')}:$declPos " +
                "resolves=${resolves[id] ?: 0} first[${firstResolve[id] ?: "<never resolved>"}]",
        )
    }

    /**
     * The rows a `narrowRendersMoreAny` divergence can be made of, i.e. the ones
     * whose rendering CONTAINS `any`, plus the whole-run totals that say whether a
     * zero here is a finding or an unarmed instrument.
     */
    fun report(tag: String, onlyAny: Boolean = true): String = buildString {
        val rows = if (onlyAny) memberRows.filter { rowFilter in it.substringBefore("  sym=") }
        else memberRows
        appendLine(
            "SYMORDER[$tag] members=${memberRows.size} anyMembers=${rows.size} " +
                "resolvedSymbols=${firstResolve.size} " +
                "refusedWrites=${firstResolve.values.count { "persisted=false" in it }} " +
                "truncatedResolutions=${firstResolve.values.count { "truncated=true" in it }}",
        )
        for (row in rows) appendLine("SYMORDER[$tag]   $row")
    }
}
