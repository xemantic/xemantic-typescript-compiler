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
 * (WARM.33) The 43 per-file INV.4 reach MEMOS, and whether transposing them
 * into ONE row of 43 statuses per node is worth taking.
 *
 * ## Why this is a census and a SIMULATION rather than an amplifier
 *
 * The candidate is a pure CACHE-LOCALITY change: the same bytes, the same
 * accesses, a different address for each. Round 759's amplifier — `r` extra
 * evaluations of the SAME thing under one timestamp pair — **cannot measure
 * one**: after the first repetition the line is L1-hot, so the amplifier prices
 * an L1 hit, which is exactly the cost the transposition exists to remove. The
 * sibling Kotlin/Rust project hit this precisely (a memo that removed 35.6% of
 * repeat reads moved its mechanism by 0.74%, because the repeat read was
 * already in L1), and round 897 records the same law from the other side — a
 * leaf-frame profile cannot see a working-set collapse either.
 *
 * So the instrument here is DETERMINISTIC and has no clock in it at all:
 *
 * 1. every memo array access is hooked at its own line (probe / ascent /
 *    write), so the ACCESS STREAM is exact rather than reconstructed;
 * 2. the stream is fed to a set-associative LRU model of BOTH layouts, at this
 *    box's geometry (L1d 32 KiB 8-way, L2 512 KiB 8-way, L3 16 MiB 16-way,
 *    64-byte lines), with a bump allocator per file so the two layouts differ
 *    in exactly the one thing under test;
 * 3. the answer is a MISS-COUNT DELTA per level, which is then priced at the
 *    level's latency.
 *
 * A miss-count delta is a counter, and counters here are deterministic — two
 * processes agreeing to the digit is this instrument's own falsifier, exactly
 * as in rounds 904 and 905.
 *
 * ## What the model does NOT contain, stated rather than hidden
 *
 * The rest of the compiler's memory traffic. Everything between two memo
 * accesses — the checker's own maps, types, symbols, AST nodes — evicts lines
 * that this model keeps. So BOTH layouts read optimistically here, and the
 * comparison is run at several cache geometries (including a deliberately
 * shrunken one standing in for that contention) so the DELTA can be read across
 * the range rather than at one point.
 */
object ReachMemoCensus {

    /** Opt-in; false in production. Set by `--reachMemoCensus`. */
    var on = false

    /**
     * The two extra memos that are NOT reach classifiers — `spineArgDepthMemo`
     * and `spineIaDepthMemo`, both `ShortArray` — get ids after
     * [ReachCensus.N] so the family the transposition would have to cover is
     * counted whole.
     */
    const val ARGDEPTH = ReachCensus.N
    const val IADEPTH = ReachCensus.N + 1
    const val N = ReachCensus.N + 2

    /** Bytes per element: the `ShortArray` memos are 2, everything else 1. */
    private val elemBytes = IntArray(N) { 1 }

    // ── the raw census ───────────────────────────────────────────────────────

    /** Accesses at the classifier's OWN node — one per consultation. */
    val probes = LongArray(N)

    /** Accesses at an ANCESTOR during the ascent. */
    val ascents = LongArray(N)

    /** Accesses that WRITE a status, one per folded chain element. */
    val writes = LongArray(N)

    /** Files seen, and the nodes in them. */
    var files = 0L
    var nodes = 0L

    /** Bytes the 45 arrays occupy, summed over files — the real footprint. */
    var footprintBytes = 0L

    /**
     * Ascent step gaps `childId - parentId`, bucketed by the LAYOUT question
     * they decide: 0 = same 64-byte block in the per-classifier layout at any
     * alignment, then powers of two. A small gap is what makes today's layout
     * cheap and the transposed one (stride 43) expensive.
     */
    val gapHistogram = LongArray(12)

    /** Consultations per NODE, histogrammed 0..10 then 11+ — round 902's law:
     *  the transposition is paid for by nodes with MORE THAN ONE consultation,
     *  so the mean 2.23 is not the quantity, the distribution is. */
    val consultsPerNode = LongArray(12)

    /** Nodes consulted at least once, and the consultations they carry. */
    var consultedNodes = 0L

    // ── per-file state ───────────────────────────────────────────────────────

    private var nodeCount = 0
    private var perNode = IntArray(0)

    /** Layout A base address of each classifier's own array, this file. */
    private val baseA = LongArray(N)

    /** Layout B base address of the single transposed array, this file. */
    private var baseB = 0L

    /** The bump allocator's cursor, so a file's arrays are contiguous the way
     *  a burst of allocations at `spine*Setup` time really is. */
    private var bump = 0L

    // ── the two modelled machines ────────────────────────────────────────────

    /** `name to (l1KiB, l2KiB, l3KiB)`; ways are 8/8/16, lines 64 B. */
    private val geometries = arrayOf(
        Triple(32, 512, 16384),   // this box, measured by `lscpu`
        Triple(32, 256, 4096),    // a standing-in-for-contention shrink
        Triple(32, 128, 1024),    // a hostile one — the delta's shape at the end
        // The one direction that could flip the verdict, so it is measured
        // rather than argued: the model cannot see the checker's own traffic
        // between two consultations, and what that traffic does is evict L1.
        // Layout A runs 45 concurrent sequential streams and needs 45 lines
        // resident; layout B runs ONE and needs two. A small L1 is where B
        // should win if it ever does.
        Triple(4, 64, 512),       // "flushed" — L1 too small for 45 streams
        Triple(8, 128, 2048),
    )
    private val geometryNames = arrayOf(
        "box(32K/512K/16M)", "shrunk(32K/256K/4M)", "hostile(32K/128K/1M)",
        "flushed(4K/64K/512K)", "mid(8K/128K/2M)",
    )

    // `val`, and cleared in place rather than reallocated: a `var` holding an
    // `Array` is a MUTABLE field to `CliModeRestoreTest`'s reflection sweep, and
    // its identity hash would then differ before and after a `reset()`.
    private val simA = Array(geometries.size) {
        Hierarchy(geometries[it].first, geometries[it].second, geometries[it].third)
    }
    private val simB = Array(geometries.size) {
        Hierarchy(geometries[it].first, geometries[it].second, geometries[it].third)
    }

    /**
     * Layout C — the candidate in its BEST possible form: a node's row PADDED
     * to 64 bytes, so it is always exactly one cache line and never straddles
     * two. It costs 42% more memory than B (64 bytes a node against 45) and it
     * is measured because a refusal taken against the strongest form of a
     * candidate is a refusal with certainty (round 903).
     */
    private val simC = Array(geometries.size) {
        Hierarchy(geometries[it].first, geometries[it].second, geometries[it].third)
    }
    private var baseC = 0L

    /** Model the JVM zeroing a fresh `ByteArray`: a write to every line. It is
     *  IDENTICAL in both layouts (43n bytes either way — the transposition does
     *  not delete one byte of it), so it cannot bias the delta; it is here
     *  because it is what leaves the caches in a realistic state at file entry. */
    var modelZeroing = true

    private class Cache(val sets: Int, val ways: Int) {
        val tag = LongArray(sets * ways) { -1L }
        val stamp = IntArray(sets * ways)
        var clock = 0
        var hits = 0L
        var misses = 0L

        fun access(line: Long): Boolean {
            val s = (line and (sets - 1).toLong()).toInt()
            val base = s * ways
            clock++
            var victim = base
            var oldest = Int.MAX_VALUE
            var i = base
            val end = base + ways
            while (i < end) {
                if (tag[i] == line) { stamp[i] = clock; hits++; return true }
                if (stamp[i] < oldest) { oldest = stamp[i]; victim = i }
                i++
            }
            misses++
            tag[victim] = line
            stamp[victim] = clock
            return false
        }
    }

    private class Hierarchy(l1KiB: Int, l2KiB: Int, l3KiB: Int) {
        val l1 = Cache(l1KiB * 1024 / 64 / 8, 8)
        val l2 = Cache(l2KiB * 1024 / 64 / 8, 8)
        val l3 = Cache(l3KiB * 1024 / 64 / 16, 16)
        var l1Hits = 0L
        var l2Hits = 0L
        var l3Hits = 0L
        var dram = 0L

        fun clear() {
            l1.tag.fill(-1L); l1.stamp.fill(0); l1.clock = 0; l1.hits = 0; l1.misses = 0
            l2.tag.fill(-1L); l2.stamp.fill(0); l2.clock = 0; l2.hits = 0; l2.misses = 0
            l3.tag.fill(-1L); l3.stamp.fill(0); l3.clock = 0; l3.hits = 0; l3.misses = 0
            l1Hits = 0; l2Hits = 0; l3Hits = 0; dram = 0
        }

        fun touch(addr: Long) {
            val line = addr ushr 6
            if (l1.access(line)) { l1Hits++; return }
            if (l2.access(line)) { l2Hits++; return }
            if (l3.access(line)) { l3Hits++; return }
            dram++
        }
    }

    fun reset() {
        for (i in 0 until N) { probes[i] = 0; ascents[i] = 0; writes[i] = 0 }
        for (i in gapHistogram.indices) gapHistogram[i] = 0
        for (i in consultsPerNode.indices) consultsPerNode[i] = 0
        files = 0; nodes = 0; footprintBytes = 0; consultedNodes = 0
        nodeCount = 0
        perNode = IntArray(0)
        bump = 0
        elemBytes.fill(1)
        elemBytes[ARGDEPTH] = 2
        elemBytes[IADEPTH] = 2
        for (h in simA) h.clear()
        for (h in simB) h.clear()
        for (h in simC) h.clear()
        baseC = 0
    }

    /**
     * Called once per source file from `spineWalkFile`, BEFORE any classifier
     * runs on it. Round 900's law: the argument is the OBJECT, and the node
     * count is derived after the gate.
     */
    fun beginFile(sf: SourceFile) {
        if (!on) return
        flushFile()
        val n = sf.nodeCount
        nodeCount = n
        perNode = if (n > 0) IntArray(n) else IntArray(0)
        files++
        nodes += n.toLong()
        // A fresh burst of allocations, one 16-byte header each, 8-byte aligned
        // — which is also what keeps the 43 arrays from aliasing onto identical
        // cache sets, an artefact a naive contiguous model would invent.
        for (c in 0 until N) {
            baseA[c] = bump + 16
            bump += 16 + align(n.toLong() * elemBytes[c])
            footprintBytes += n.toLong() * elemBytes[c]
        }
        baseB = bump + 16
        bump += 16 + align(n.toLong() * N)
        baseC = bump + 16
        bump += 16 + align(n.toLong() * 64)
        if (modelZeroing) {
            for (c in 0 until N) zero(baseA[c], n.toLong() * elemBytes[c], 0)
            zero(baseB, n.toLong() * N, 1)
            zero(baseC, n.toLong() * 64, 2)
        }
    }

    private fun align(bytes: Long): Long = (bytes + 7L) and 7L.inv()

    private fun zero(base: Long, bytes: Long, arm: Int) {
        val sim = when (arm) { 0 -> simA; 1 -> simB; else -> simC }
        var a = base
        val end = base + bytes
        while (a < end) {
            for (h in sim) h.touch(a)
            a += 64
        }
    }

    private fun flushFile() {
        if (nodeCount == 0) return
        var consulted = 0L
        for (i in 0 until nodeCount) {
            val k = perNode[i]
            if (k > 0) {
                consulted++
                consultsPerNode[if (k >= 11) 11 else k]++
            }
        }
        consultsPerNode[0] += nodeCount - consulted
        consultedNodes += consulted
        nodeCount = 0
    }

    /** A PROBE at the classifier's own node — the consultation itself. */
    fun p(c: Int, id: Int) {
        probes[c]++
        if (id >= 0 && id < nodeCount) perNode[id]++
        touch(c, id)
    }

    /** An ASCENT probe at an ancestor; [childId] is the node it came from, so
     *  the nodeId GAP — the quantity that decides whether today's layout keeps
     *  an ascent inside one cache line — is recorded where it is known. */
    fun s(c: Int, id: Int, childId: Int) {
        ascents[c]++
        val gap = if (childId >= id) childId - id else id - childId
        var b = 0
        var g = gap
        while (g > 0 && b < 11) { b++; g = g shr 1 }
        gapHistogram[b]++
        touch(c, id)
    }

    /**
     * The two interleaved classifiers (`UResExpr`/`UResType`) probe inside
     * their ascent loop, so the SAME line is the consultation on the first
     * iteration and an ancestor probe afterwards — [hops] is the loop's own
     * counter and is what separates them. Without this the per-node
     * consultation histogram would count ancestors as consultations.
     */
    fun pa(c: Int, id: Int, hops: Int) {
        if (hops == 0) { p(c, id); return }
        ascents[c]++
        touch(c, id)
    }

    /** A WRITE of a folded status. */
    fun w(c: Int, id: Int) {
        writes[c]++
        touch(c, id)
    }

    private fun touch(c: Int, id: Int) {
        if (id < 0 || id >= nodeCount) return
        val a = baseA[c] + id.toLong() * elemBytes[c]
        for (h in simA) h.touch(a)
        val b = baseB + id.toLong() * N + c
        for (h in simB) h.touch(b)
        val cAddr = baseC + id.toLong() * 64 + c
        for (h in simC) h.touch(cAddr)
    }

    fun report(): String = buildString {
        flushFile()
        var tp = 0L; var ta = 0L; var tw = 0L
        for (i in 0 until N) { tp += probes[i]; ta += ascents[i]; tw += writes[i] }
        val total = tp + ta + tw
        appendLine("== (WARM.33) the 43 per-file INV.4 reach MEMOS — access census ==")
        appendLine("  files=$files nodes=$nodes footprint=${footprintBytes / 1024 / 1024} MiB ($footprintBytes B)")
        appendLine("  accesses: probe=$tp ascent=$ta write=$tw TOTAL=$total")
        appendLine("  consulted nodes=$consultedNodes; consults/consulted node=" +
            (if (consultedNodes == 0L) 0.0 else tp.toDouble() / consultedNodes))
        appendLine("  consults per NODE, buckets 0..10 then 11+: ${consultsPerNode.joinToString(" ")}")
        appendLine("  ascent nodeId gap, buckets 0,1,2-3,4-7,...,1024+: ${gapHistogram.joinToString(" ")}")
        appendLine("  per-classifier probe/ascent/write:")
        val order = (0 until N).sortedByDescending { probes[it] + ascents[it] + writes[it] }
        for (i in order) {
            val t = probes[i] + ascents[i] + writes[i]
            if (t == 0L) continue
            val nm = if (i < ReachCensus.N) ReachCensus.names[i] else if (i == ARGDEPTH) "ArgDepth" else "IaDepth"
            appendLine("    ${nm.padEnd(12)} probe=${probes[i]} ascent=${ascents[i]} write=${writes[i]}")
        }
        appendLine(
            "  LAYOUT SIMULATION — A = 45 arrays (today), B = one row of $N per node, " +
                "C = one row PADDED to 64 B per node"
        )
        for (g in geometries.indices) {
            val a = simA[g]; val b = simB[g]; val c = simC[g]
            appendLine("    ${geometryNames[g]}")
            appendLine("      A  l1=${a.l1Hits} l2=${a.l2Hits} l3=${a.l3Hits} dram=${a.dram}")
            appendLine("      B  l1=${b.l1Hits} l2=${b.l2Hits} l3=${b.l3Hits} dram=${b.dram}")
            appendLine("      C  l1=${c.l1Hits} l2=${c.l2Hits} l3=${c.l3Hits} dram=${c.dram}")
            appendLine("      B-A l1=${b.l1Hits - a.l1Hits} l2=${b.l2Hits - a.l2Hits} " +
                "l3=${b.l3Hits - a.l3Hits} dram=${b.dram - a.dram}")
            appendLine("      C-A l1=${c.l1Hits - a.l1Hits} l2=${c.l2Hits - a.l2Hits} " +
                "l3=${c.l3Hits - a.l3Hits} dram=${c.dram - a.dram}")
        }
    }
}
