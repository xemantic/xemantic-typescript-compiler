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
 * (SETUP.2) — the produced-vs-consumed census of `buildFileLocalTypeMaps`,
 * the 636 ms (2.2% of the compile) setup pass round 802 named
 * (`docs/perf/setup-phase-and-huge-methods.md` § 1–2).
 *
 * The pass eagerly `getTypeOfSymbol`s every file-level function / class /
 * interface / enum / type alias / import alias and every annotated variable in
 * the program, into `fileLocalTypeMaps`, whose ONLY reader is
 * `getTypeOfIdentifier`. Deferring it is round 788's shape, and round 788's law
 * forbids pricing it by counting: `getTypeOfSymbol` MEMOISES into
 * `symbolTypes`, so a deferral MOVES the resolution to whichever pass asks
 * first. **The recoverable part is only the symbols nothing ever asks for**,
 * and this object measures exactly that:
 *
 *  * `calls` vs `distinct` at the single read site — round 800's ratio;
 *  * the entries that are **never read** through the map at all;
 *  * of those, the ones whose symbol is **never asked again** by any later
 *    `getTypeOfSymbol` call — the only population a deferral DELETES rather
 *    than moves;
 *  * the wall time of those direct resolutions, and how much of it is
 *    *transitively* re-asked later (i.e. would move even if the direct
 *    resolution vanished).
 *
 * Off (`on == false`, the default) every hook is a static boolean read and a
 * not-taken branch: the probe is behaviour-free, as INV.0 requires.
 */
object FltmCensus {

    /** Master switch; `--fltmCensus`. */
    var on: Boolean = false

    /** True while `buildFileLocalTypeMaps` is running. */
    private var inSetup: Boolean = false

    /** True once it has finished — arms the "asked again later" hook. */
    private var setupDone: Boolean = false

    /** Symbol id whose DIRECT resolution is currently on the stack, or -1. */
    private var owner: Int = -1

    /** `getTypeOfSymbol` entries made from the pass's own two call sites. */
    var directResolves: Long = 0
        private set

    /** All `getTypeOfSymbol` entries (nested included) made during the pass. */
    var setupAsks: Long = 0
        private set

    /** Map lookups at the read site that served a type / that missed. */
    var readHits: Long = 0
        private set
    var readMisses: Long = 0
        private set

    /** `"file|name"` of every stored entry -> the symbol id behind it. */
    private val storedKeys = HashMap<String, Int>()

    /** `"file|name"` of every entry the read site has SERVED (distinct). */
    private val readKeys = HashSet<String>()

    /** Symbol ids the pass resolved DIRECTLY. */
    private val directSymbols = HashSet<Int>()

    /** Every symbol id touched during the pass, nested resolutions included. */
    private val touched = HashSet<Int>()

    /** touched symbol id -> the DIRECT symbol whose resolution first minted it. */
    private val firstTouchOwner = HashMap<Int, Int>()

    /** Of [touched], the ids some later `getTypeOfSymbol` asks for again. */
    private val askedLater = HashSet<Int>()

    /** Entries the pass stored (the map's whole population). */
    val storedEntries: Int get() = storedKeys.size

    /** Distinct entries the read site ever served — round 800's `distinct`. */
    val distinctReads: Int get() = readKeys.size

    /** Stored entries no read ever served. */
    val neverReadEntries: Int get() = storedKeys.keys.count { it !in readKeys }

    /** Direct symbol id -> nanos of its own (self+nested) resolution. */
    private val directNanos = HashMap<Int, Long>()

    /**
     * Direct symbol id -> which BRANCH of the pass resolved it. Load-bearing for
     * the deferral question: only the `typealias` branch can emit (TS2589/TS2615
     * are gated on `taDecl != null`), and only the `var` branch's annotation can,
     * so the `decl` branch is the part whose resolution is pure map building.
     */
    private val directKind = HashMap<Int, String>()

    fun reset() {
        inSetup = false
        setupDone = false
        owner = -1
        directResolves = 0
        setupAsks = 0
        readHits = 0
        readMisses = 0
        storedKeys.clear()
        readKeys.clear()
        directSymbols.clear()
        touched.clear()
        firstTouchOwner.clear()
        askedLater.clear()
        directNanos.clear()
        directKind.clear()
    }

    fun beginSetup() {
        inSetup = true
    }

    fun endSetup() {
        inSetup = false
        setupDone = true
    }

    /**
     * Opens ONE direct resolution; returns the previous owner, to be handed back
     * to [leaveDirect]. The caller's timestamp pair costs ~90 ns against a
     * population of ~10^4, i.e. ~1 ms against a 636 ms row — and it is the only
     * way to turn the never-asked POPULATION into a price (CLAUDE.md: a count of
     * avoidable work is not a measure of it).
     */
    fun enterDirect(symbolId: Int, kind: String): Int {
        val prev = owner
        if (prev == -1) {
            directResolves++
            directSymbols.add(symbolId)
            directKind[symbolId] = kind
        }
        if (prev == -1) owner = symbolId
        return prev
    }

    fun leaveDirect(symbolId: Int, prev: Int, nanos: Long) {
        if (prev == -1) directNanos[symbolId] = (directNanos[symbolId] ?: 0L) + nanos
        owner = prev
    }

    /** Every `getTypeOfSymbol` entry, before its memo fast path. */
    fun noteAsk(symbolId: Int) {
        if (inSetup) {
            setupAsks++
            if (touched.add(symbolId) && owner != -1) firstTouchOwner[symbolId] = owner
        } else if (setupDone && symbolId in touched) {
            askedLater.add(symbolId)
        }
    }

    /** One entry stored into a file's type map. */
    fun noteStored(fileName: String, name: String, symbolId: Int) {
        storedKeys["$fileName|$name"] = symbolId
    }

    /** One lookup at `getTypeOfIdentifier`'s `fileLocalTypeMaps` read. */
    fun noteRead(fileName: String, name: String, hit: Boolean) {
        if (hit) {
            readHits++
            readKeys.add("$fileName|$name")
        } else {
            readMisses++
        }
    }

    fun report(): String = buildString {
        appendLine("== (SETUP.2) buildFileLocalTypeMaps produced-vs-consumed census ==")
        appendLine(
            "  direct resolves: $directResolves   entries stored: ${storedKeys.size}   " +
                "getTypeOfSymbol entries during the pass: $setupAsks " +
                "(${touched.size} distinct symbols touched)"
        )
        val distinct = readKeys.size
        appendLine(
            "  map reads: calls=$readHits  distinct=$distinct  misses=$readMisses" +
                if (distinct > 0) "   calls/distinct=${fmt(readHits.toDouble() / distinct)}" else ""
        )
        val neverRead = storedKeys.keys.filter { it !in readKeys }
        appendLine(
            "  entries NEVER read through the map: ${neverRead.size} of ${storedKeys.size} " +
                "(${pct(neverRead.size, storedKeys.size)})"
        )
        // Round 788's law applied to the never-read entries: their symbol's type
        // may still be asked by another path, in which case a deferral MOVES the
        // resolution instead of deleting it.
        val neverReadIds = neverRead.mapNotNull { storedKeys[it] }.toHashSet()
        val movedIds = neverReadIds.filter { it in askedLater }
        appendLine(
            "  of those, symbol asked again later anyway (=> MOVED): ${movedIds.size} " +
                "(${pct(movedIds.size, neverReadIds.size)})"
        )
        val prizeIds = neverReadIds.filter { it !in askedLater }
        appendLine(
            "  never read AND never asked again (=> the DELETABLE population): " +
                "${prizeIds.size} (${pct(prizeIds.size, neverReadIds.size)} of never-read, " +
                "${pct(prizeIds.size, directSymbols.size)} of direct resolves)"
        )
        // Price it. The direct nanos are self+nested, so this is an UPPER bound:
        // a nested resolution a deletable symbol drove may itself be asked later.
        val prizeNanos = prizeIds.sumOf { directNanos[it] ?: 0L }
        val allNanos = directNanos.values.sum()
        appendLine(
            "  direct-resolve wall: total ${ms(allNanos)} ms   " +
                "deletable population ${ms(prizeNanos)} ms (${pct2(prizeNanos, allNanos)})"
        )
        // How much of that upper bound MOVES: nested symbols first minted under a
        // deletable direct resolution that some later pass asks for anyway.
        var ownedTotal = 0
        var ownedMoved = 0
        val prizeSet = prizeIds.toHashSet()
        for ((child, ownerId) in firstTouchOwner) {
            if (ownerId in prizeSet && child != ownerId) {
                ownedTotal++
                if (child in askedLater) ownedMoved++
            }
        }
        appendLine(
            "  nested symbols first minted under a deletable resolution: $ownedTotal, " +
                "of which asked later anyway (=> that part MOVES): $ownedMoved " +
                "(${pct(ownedMoved, ownedTotal)})"
        )
        appendLine(
            "  touched symbols asked again after the pass: ${askedLater.size} of " +
                "${touched.size} (${pct(askedLater.size, touched.size)})"
        )
        // The stored-entry view above misses the LARGER population: a direct
        // resolution that produced `anyType`/`errorType` stores no entry at all,
        // so it can never be "read", and its whole cost is recoverable exactly
        // when nothing asks the symbol again. This is the full deletable set.
        val readIds = readKeys.mapNotNull { storedKeys[it] }.toHashSet()
        val storedIds = storedKeys.values.toHashSet()
        val noStore = directSymbols.filter { it !in storedIds }
        appendLine(
            "  direct resolves that stored NO entry (any/errorType): ${noStore.size} of " +
                "${directSymbols.size} (${pct(noStore.size, directSymbols.size)}), " +
                "${ms(noStore.sumOf { directNanos[it] ?: 0L })} ms"
        )
        val fullPrize = directSymbols.filter { it !in readIds && it !in askedLater }
        val fullPrizeNanos = fullPrize.sumOf { directNanos[it] ?: 0L }
        appendLine(
            "  FULL deletable population (never read AND never asked again): " +
                "${fullPrize.size} of ${directSymbols.size} " +
                "(${pct(fullPrize.size, directSymbols.size)}), " +
                "${ms(fullPrizeNanos)} ms of ${ms(directNanos.values.sum())} ms " +
                "(${pct2(fullPrizeNanos, directNanos.values.sum())})"
        )
        var fullOwned = 0
        var fullOwnedMoved = 0
        val fullSet = fullPrize.toHashSet()
        for ((child, ownerId) in firstTouchOwner) {
            if (ownerId in fullSet && child != ownerId) {
                fullOwned++
                if (child in askedLater) fullOwnedMoved++
            }
        }
        appendLine(
            "    nested symbols first minted under it: $fullOwned, asked later anyway " +
                "(=> MOVES): $fullOwnedMoved (${pct(fullOwnedMoved, fullOwned)})"
        )
        // Per BRANCH. `typealias` and `var` are the two that can EMIT (the bail
        // detector); `decl` (function/class/interface/enum/import alias) is pure
        // map building and is the only branch a deferral could touch without
        // losing a diagnostic.
        for (kind in listOf("typealias", "decl", "var")) {
            val ids = directSymbols.filter { directKind[it] == kind }
            val del = ids.filter { it !in readIds && it !in askedLater }
            appendLine(
                "  branch '$kind': ${ids.size} resolves, " +
                    "${ms(ids.sumOf { directNanos[it] ?: 0L })} ms   " +
                    "deletable ${del.size} (${pct(del.size, ids.size)}), " +
                    "${ms(del.sumOf { directNanos[it] ?: 0L })} ms"
            )
        }
    }

    private fun pct(n: Int, d: Int): String =
        if (d == 0) "n/a" else "${fmt(100.0 * n / d)}%"

    private fun pct2(n: Long, d: Long): String =
        if (d == 0L) "n/a" else "${fmt(100.0 * n / d)}%"

    private fun ms(nanos: Long): String = fmt(nanos / 1_000_000.0)

    private fun fmt(v: Double): String {
        val scaled = (v * 10).toLong()
        return "${scaled / 10}.${scaled % 10}"
    }
}
