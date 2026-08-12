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
 * (WARM.23) — the price of the map-key candidates round 894's census ranked, taken
 * BEFORE any of them is built (CLAUDE.md's first law: price the population first).
 *
 * `docs/perf/warm-hash-owner-census.md` § 9 attaches an UPPER BOUND to each
 * candidate — "if this owner's map work went to zero" — and says so loudly. This
 * object turns three of them into numbers a decision can be made on:
 *
 *  * **(3) `nodeToFlow`** — a `mutableMapOf<Long, FlowNode>` (LinkedHashMap +
 *    boxed keys) written once per recorded node and read back once per node by
 *    `FlowGraph`'s side-table fill. Priced by REPLAY: the file's real key
 *    sequence is filled into a fresh `mutableMapOf` and a fresh [LongKeyMap] and
 *    read back from both, under one timestamp pair per container per rep, ABBA
 *    within the file so a drift lands on both arms. This is the only shape that
 *    answers what a swap RECOVERS rather than what the old container COSTS — a
 *    `LongKeyMap` probe is not free, and round 894 § 9(3) says to deflate the
 *    46.6 ms ceiling deliberately.
 *  * **(4)/(5) the in-progress sentinel sets** — `symbolTypeResolutionInProgress`
 *    (`HashSet<Int>`), `nodeTypeResolutionInProgress` (`HashSet<TypeNode>`, a
 *    round-471 deep-hashing AST key) and `memberResolutionInProgress`. Both the
 *    population (adds) and the MAX LIVE SIZE, because round 890's law is that a
 *    transient add/remove set's table is bounded by its live size, not by the
 *    keys it ever saw — and the live size is what decides whether a linear-scan
 *    int stack can replace a hash set at all.
 *  * **(2a) `perFileScope[fileName]`** — counted (how many full file-PATH hashes
 *    a rebuild pays) and priced by round 759's AMPLIFICATION, because one probe
 *    is ~50 ns against a ~90 ns timestamp pair. `--perFileScopeAmp N` performs N
 *    probes under ONE pair; two values of N cancel the boundary algebraically.
 *
 * Off (`on == false`, `perFileScopeReads == 0`, `flowReplayReps == 0` — the
 * defaults) every hook is a static read and a not-taken branch, as INV.0
 * requires.
 */
object MapCensus {

    /** Master switch for the COUNTERS; `--mapCensus`. */
    var on: Boolean = false

    /**
     * Probes of `perFileScope` performed under ONE timestamp pair; `0` = OFF, a
     * NEGATIVE value is the in-situ EMPTY bracket. `--perFileScopeAmp N`.
     * Carries [GlobalsAmp]'s warning: an in-situ empty span has over-read the
     * pair by 3.6-4.4x (rounds 734/735), so the empty arm BOUNDS the boundary
     * rather than measuring it, and the slope from two positive N is the answer.
     */
    var perFileScopeReads: Int = 0

    /** Replay reps per file for the `nodeToFlow` container A/B; `0` = OFF. */
    var flowReplayReps: Int = 0

    // ---- (4)/(5) in-progress sentinels ---------------------------------

    var symAdds: Long = 0
    var symReentries: Long = 0
    var symMaxLive: Int = 0
    private var symLive: Int = 0

    var nodeAdds: Long = 0
    var nodeReentries: Long = 0
    var nodeMaxLive: Int = 0
    private var nodeLive: Int = 0

    var memberAdds: Long = 0
    var memberReentries: Long = 0
    var memberMaxLive: Int = 0
    private var memberLive: Int = 0

    fun symEnter(added: Boolean) {
        symAdds++
        if (!added) { symReentries++; return }
        symLive++
        if (symLive > symMaxLive) symMaxLive = symLive
    }

    fun symLeave() { symLive-- }

    fun nodeEnter(added: Boolean) {
        nodeAdds++
        if (!added) { nodeReentries++; return }
        nodeLive++
        if (nodeLive > nodeMaxLive) nodeMaxLive = nodeLive
    }

    fun nodeLeave() { nodeLive-- }

    fun memberEnter(added: Boolean) {
        memberAdds++
        if (!added) { memberReentries++; return }
        memberLive++
        if (memberLive > memberMaxLive) memberMaxLive = memberLive
    }

    fun memberLeave() { memberLive-- }

    // ---- (2a) perFileScope ----------------------------------------------

    /** Production probes of `perFileScope` that reached the MAP. */
    var perFileProbes: Long = 0

    /** Reads the one-entry reference-compared memo answered without a map probe. */
    var perFileMemoHits: Long = 0

    var perFileAmpNanos: Long = 0
    var perFileAmpCalls: Long = 0

    /** Consumes amplified results so the JIT cannot elide the reads. */
    var sink: Long = 0

    // ---- (3) nodeToFlow replay -------------------------------------------

    var replayFiles: Long = 0
    var replayKeys: Long = 0
    var legacyPutNanos: Long = 0
    var legacyGetNanos: Long = 0
    var longPutNanos: Long = 0
    var longGetNanos: Long = 0
    /** Reps actually run, so a per-rebuild figure is nanos / reps. */
    var replayReps: Long = 0

    fun reset() {
        symAdds = 0; symReentries = 0; symMaxLive = 0; symLive = 0
        nodeAdds = 0; nodeReentries = 0; nodeMaxLive = 0; nodeLive = 0
        memberAdds = 0; memberReentries = 0; memberMaxLive = 0; memberLive = 0
        perFileProbes = 0; perFileMemoHits = 0
        perFileAmpNanos = 0; perFileAmpCalls = 0; sink = 0
        replayFiles = 0; replayKeys = 0; replayReps = 0
        legacyPutNanos = 0; legacyGetNanos = 0; longPutNanos = 0; longGetNanos = 0
    }

    /**
     * Replays one file's real `nodeToFlow` key sequence into both containers.
     *
     * Each rep builds a FRESH container of each kind, so every insert is a
     * distinct-key insert that pays its share of table growth — amplifying by
     * re-putting the SAME key into ONE container would have measured an
     * overwrite, which is not the operation production performs. The two arms
     * alternate order per rep (ABBA), so a drift inside the file lands on both.
     *
     * The value stored is a shared object: what differs between the arms is the
     * KEY handling (boxing, entry linking, bucket probing), and giving both arms
     * the same value keeps that the only difference.
     */
    fun replayFlowKeys(keys: LongArray) {
        val reps = flowReplayReps
        if (reps <= 0 || keys.isEmpty()) return
        replayFiles++
        replayKeys += keys.size.toLong()
        val value = Unit
        var rep = 0
        while (rep < reps) {
            replayReps++
            if (rep % 2 == 0) {
                replayLegacy(keys, value)
                replayLong(keys, value)
            } else {
                replayLong(keys, value)
                replayLegacy(keys, value)
            }
            rep++
        }
    }

    private fun replayLegacy(keys: LongArray, value: Any) {
        var t0 = PassTiming.nowNanos()
        val m: MutableMap<Long, Any> = mutableMapOf()
        var i = 0
        while (i < keys.size) { m[keys[i]] = value; i++ }
        legacyPutNanos += PassTiming.nowNanos() - t0
        t0 = PassTiming.nowNanos()
        var seen = 0L
        i = 0
        while (i < keys.size) { if (m[keys[i]] != null) seen++; i++ }
        legacyGetNanos += PassTiming.nowNanos() - t0
        sink += seen
    }

    private fun replayLong(keys: LongArray, value: Any) {
        var t0 = PassTiming.nowNanos()
        val m = LongKeyMap<Any>(256)
        var i = 0
        while (i < keys.size) { m.put(keys[i], value); i++ }
        longPutNanos += PassTiming.nowNanos() - t0
        t0 = PassTiming.nowNanos()
        var seen = 0L
        i = 0
        while (i < keys.size) { if (m.get(keys[i]) != null) seen++; i++ }
        longGetNanos += PassTiming.nowNanos() - t0
        sink += seen
    }

    fun report(): String = buildString {
        appendLine("== (WARM.23) map-key candidate census ==")
        appendLine(
            "  (4) symbolTypeResolutionInProgress: adds=$symAdds re-entries=$symReentries " +
                "MAX LIVE=$symMaxLive"
        )
        appendLine(
            "  (5) nodeTypeResolutionInProgress:   adds=$nodeAdds re-entries=$nodeReentries " +
                "MAX LIVE=$nodeMaxLive"
        )
        appendLine(
            "  (-) memberResolutionInProgress:     adds=$memberAdds re-entries=$memberReentries " +
                "MAX LIVE=$memberMaxLive"
        )
        appendLine(
            "  (2a) perFileScope map probes: $perFileProbes   memo hits: $perFileMemoHits   " +
                "reads: ${perFileProbes + perFileMemoHits}"
        )
        if (perFileAmpCalls > 0) {
            val per = perFileAmpNanos / perFileAmpCalls
            appendLine(
                "  (2a) amplified: reads/probe=$perFileScopeReads bracketed=$perFileAmpCalls " +
                    "total ${perFileAmpNanos / 1_000_000} ms   p($perFileScopeReads)=$per ns " +
                    "= cold + ${perFileScopeReads - 1} * warm + boundary   sink=$sink"
            )
            appendLine("       solve `warm` from TWO runs: (p(r2) - p(r1)) / (r2 - r1)")
        }
        if (replayReps > 0) {
            // [replayKeys] accumulates ONCE per file, so it is already a
            // per-rebuild figure; the NANOS accumulate once per rep and are the
            // only thing divided by the rep count.
            appendLine(
                "  (3) nodeToFlow replay: files=$replayFiles keys/rebuild=$replayKeys " +
                    "reps=$replayReps (mean keys per file ~${replayKeys / maxOf(replayFiles, 1)})"
            )
            val n = maxOf(flowReplayReps.toLong(), 1)
            appendLine(
                "      mutableMapOf<Long,·>  put ${legacyPutNanos / n / 1_000_000.0} ms   " +
                    "get ${legacyGetNanos / n / 1_000_000.0} ms   " +
                    "TOTAL ${(legacyPutNanos + legacyGetNanos) / n / 1_000_000.0} ms per rebuild"
            )
            appendLine(
                "      LongKeyMap            put ${longPutNanos / n / 1_000_000.0} ms   " +
                    "get ${longGetNanos / n / 1_000_000.0} ms   " +
                    "TOTAL ${(longPutNanos + longGetNanos) / n / 1_000_000.0} ms per rebuild"
            )
            appendLine(
                "      RECOVERABLE (legacy - long) = " +
                    "${(legacyPutNanos + legacyGetNanos - longPutNanos - longGetNanos) / n / 1_000_000.0} ms per rebuild"
            )
        }
    }
}
