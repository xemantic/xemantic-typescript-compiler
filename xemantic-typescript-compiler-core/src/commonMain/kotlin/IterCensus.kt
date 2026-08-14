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
 * (WARM.32) The ITERATOR-ALLOCATION family — the census and the two-arm
 * amplifier that price it. Everything here is OFF by default and, when off,
 * costs one static `Boolean` read at each of the two hook sites.
 *
 * ## The mechanism
 *
 * Kotlin's `Iterable.any`/`all`/`none`/`forEach` are `inline`, but their bodies
 * are `for (element in this)` on an `Iterable` receiver — and the Kotlin
 * compiler only lowers a `for` to a counted loop for arrays, `CharSequence`s
 * and ranges. Over a `List` it asks the receiver for a **heap iterator** and
 * then pays `hasNext()`/`next()` interface dispatch per element. An indexed
 * `for (i in xs.indices)` loop removes the allocation and both dispatches; it
 * reads `size` once and then does a bounds-checked `get`.
 *
 * ## The two populations
 *
 * * [walkList] — the 70 list child positions of [forEachChild], which runs once
 *   per node on three separate sweeps (`spineWalkFile`, `Binder.pushChildren`,
 *   `FlowGraph`'s side-table fill). `forEachChild` is #5 in the warm leaf table
 *   at 1.40%.
 * * [anyIdentical] — the 145 `.any { it === child }` membership tests in the
 *   INV.4 edge classifiers, which run per (parent, child) edge against round
 *   875's 3.32 M edge evaluations at 13.3 ns each (44 ms, the family's ceiling).
 *
 * ## THE CAVEAT THAT DECIDES THE INSTRUMENT
 *
 * **The value here is NOT the allocated bytes.** Round 801 removed 367,189
 * `String` allocations for exactly **0 ms**, and round 893 measured warm GC at
 * ~92-98 ms of a ~5.4 s rebuild (~1.7%) with the FASTER binary taking MORE
 * pauses. So this family must be priced in TIME — a per-operation premium from
 * an amplifier — and never in MB. There is no allocation counter here on
 * purpose.
 *
 * ## The instrument
 *
 * The amplifier is IN SITU (rounds 903/904's shape, not a replay): both arms
 * run adjacent at the real call site, over the real population, at the real
 * cache state, in ABBA rotation. Its answer is a SLOPE, so a process must run
 * two `r` and `p(r) = cost + boundary / r` is fitted PER ARM — round 904 showed
 * a single-`r` `A - B` over-reads by up to 23%, because the two arms'
 * timestamp-pair boundaries are not equal and cancel only in the slope.
 *
 * The one bias this shape carries and cannot remove: production runs arm A at
 * 70 (resp. 145) SEPARATE call sites, each of which may see one concrete `List`
 * implementation and so inline `iterator()`/`hasNext`/`next` and scalar-replace
 * the iterator; the amplifier runs it at ONE site that sees every
 * implementation. [fecClassSamples] censuses the concrete classes precisely so
 * that bias can be bounded rather than assumed.
 */
object IterCensus {

    /**
     * The single gate both hot hooks read. Armed by either `--iterCensus` or
     * `--iterAmp N`, so production pays ONE static read and one not-taken
     * branch per hook, never two.
     */
    var on: Boolean = false

    /** `--iterCensus`: counts and histograms only, no timestamp pair. */
    var census: Boolean = false

    /**
     * `--iterAmp N`: repetitions per arm per call, `0` = OFF. Two values of `N`
     * are required to separate the per-operation cost from the boundary.
     */
    var amp: Int = 0

    // ── population 1: forEachChild's list child positions ────────────────────

    /** `walkList` invocations with a NON-null list (a null position does nothing). */
    var fecCalls: Long = 0

    /** Elements those calls handed to `action`. */
    var fecElements: Long = 0

    /** List length, buckets 0..10 then 11+. Bucket 0 is the EMPTY-list population. */
    val fecSizeHistogram = LongArray(12)

    /**
     * Concrete `List` implementations seen, sampled every 1,024th call so the
     * `KClass` lookup cannot move the census's own cost into the compile. The
     * question it answers is whether production's 70 call sites are
     * monomorphic — if they are, this amplifier's single site is megamorphic
     * where production is not, and arm A is an OVER-read.
     */
    val fecClassSamples = HashMap<String, Long>()

    // ── population 2: the INV.4 edge classifiers' identity membership tests ──

    /** `anyIdentical` invocations. */
    var anyCalls: Long = 0

    /** Elements actually VISITED — a hit stops the scan, so this is what is paid. */
    var anySteps: Long = 0

    /** List lengths, i.e. what a full scan would visit. `anySteps <= anyLength`. */
    var anyLength: Long = 0

    /** Calls that found the target. */
    var anyHits: Long = 0

    /** List length, buckets 0..10 then 11+. */
    val anySizeHistogram = LongArray(12)

    // ── the amplifier ────────────────────────────────────────────────────────

    var ampCalls: Long = 0
    var ampIterNanos: Long = 0
    var ampIdxNanos: Long = 0

    /**
     * The arithmetic falsifiers (round 759). Both must be an exact multiple of
     * `r` — otherwise a loop was elided — and they must be EQUAL to each other,
     * which is the equivalence assertion: the two arms visit the same elements
     * in the same order and fold the same value.
     */
    var ampIterSink: Long = 0
    var ampIdxSink: Long = 0

    var ampAnyCalls: Long = 0
    var ampAnyIterNanos: Long = 0
    var ampAnyIdxNanos: Long = 0
    var ampAnyIterSink: Long = 0
    var ampAnyIdxSink: Long = 0

    /** A global sink, so nothing above is dead by escape analysis. */
    var sink: Long = 0

    fun reset() {
        // Like every other probe object here, `reset()` clears COUNTERS only —
        // the mode flags are set by the CLI arm and by `tierBegin`.
        fecCalls = 0; fecElements = 0
        for (i in fecSizeHistogram.indices) fecSizeHistogram[i] = 0
        fecClassSamples.clear()
        anyCalls = 0; anySteps = 0; anyLength = 0; anyHits = 0
        for (i in anySizeHistogram.indices) anySizeHistogram[i] = 0
        ampCalls = 0; ampIterNanos = 0; ampIdxNanos = 0
        ampIterSink = 0; ampIdxSink = 0
        ampAnyCalls = 0; ampAnyIterNanos = 0; ampAnyIdxNanos = 0
        ampAnyIterSink = 0; ampAnyIdxSink = 0
        sink = 0
    }

    /**
     * The [walkList] hook. Round 900's law: the LIST is the argument and every
     * derived quantity ([List.size], the `KClass`) is read INSIDE, after the
     * gate — a `note(xs.size)` would have made production pay `size` forever.
     */
    fun noteList(xs: List<Node>) {
        if (census) {
            val n = xs.size
            fecCalls++
            fecElements += n.toLong()
            fecSizeHistogram[if (n >= 11) 11 else n]++
            if ((fecCalls and 1023L) == 0L) {
                val k = xs::class.simpleName ?: "?"
                fecClassSamples[k] = (fecClassSamples[k] ?: 0L) + 1L
            }
        }
        val r = amp
        if (r > 0) ampList(xs, r)
    }

    /** The [anyIdentical] hook; same law, same shape. */
    fun noteAny(xs: List<Node>, target: Node) {
        if (census) {
            val n = xs.size
            anyCalls++
            anyLength += n.toLong()
            anySizeHistogram[if (n >= 11) 11 else n]++
            var i = 0
            while (i < n) {
                i++
                if (xs[i - 1] === target) { anyHits++; break }
            }
            anySteps += i.toLong()
        }
        val r = amp
        if (r > 0) ampAny(xs, target, r)
    }

    /**
     * ABBA inside the run: the two arms alternate which goes first, so a drift
     * across the compile lands on both equally. Round 891's mirrored rotation
     * is the caller's job (two processes, `iteramp8,iteramp24,...` reversed).
     */
    private fun ampList(xs: List<Node>, r: Int) {
        ampCalls++
        if ((ampCalls and 1L) == 0L) { ampIter(xs, r); ampIdx(xs, r) }
        else { ampIdx(xs, r); ampIter(xs, r) }
    }

    /** Arm A — exactly what `xs.forEach(action)` / `for (e in xs)` lowers to. */
    private fun ampIter(xs: List<Node>, r: Int) {
        val t0 = PassTiming.nowNanos()
        var s = 0L
        var i = 0
        while (i < r) {
            for (e in xs) s += (e as NodeBase).kindId.toLong()
            i++
        }
        ampIterNanos += PassTiming.nowNanos() - t0
        ampIterSink += s
        sink += s
    }

    /** Arm B — the successor, written exactly as the fix would write it. */
    private fun ampIdx(xs: List<Node>, r: Int) {
        val t0 = PassTiming.nowNanos()
        var s = 0L
        var i = 0
        while (i < r) {
            for (j in xs.indices) s += (xs[j] as NodeBase).kindId.toLong()
            i++
        }
        ampIdxNanos += PassTiming.nowNanos() - t0
        ampIdxSink += s
        sink += s
    }

    private fun ampAny(xs: List<Node>, target: Node, r: Int) {
        ampAnyCalls++
        if ((ampAnyCalls and 1L) == 0L) { ampAnyIter(xs, target, r); ampAnyIdx(xs, target, r) }
        else { ampAnyIdx(xs, target, r); ampAnyIter(xs, target, r) }
    }

    /** Arm A — exactly what `xs.any { it === target }` lowers to. */
    private fun ampAnyIter(xs: List<Node>, target: Node, r: Int) {
        val t0 = PassTiming.nowNanos()
        var s = 0L
        var i = 0
        while (i < r) {
            for (e in xs) if (e === target) { s++; break }
            i++
        }
        ampAnyIterNanos += PassTiming.nowNanos() - t0
        ampAnyIterSink += s
        sink += s
    }

    /** Arm B — the successor, written exactly as the fix would write it. */
    private fun ampAnyIdx(xs: List<Node>, target: Node, r: Int) {
        val t0 = PassTiming.nowNanos()
        var s = 0L
        var i = 0
        while (i < r) {
            for (j in xs.indices) if (xs[j] === target) { s++; break }
            i++
        }
        ampAnyIdxNanos += PassTiming.nowNanos() - t0
        ampAnyIdxSink += s
        sink += s
    }

    fun report(): String = buildString { report(this) }

    private fun report(out: StringBuilder) {
        if (census) {
            out.appendLine("  (WARM.32) the ITERATOR-ALLOCATION family — census")
            out.appendLine(
                "    forEachChild list positions: calls=$fecCalls elements=$fecElements " +
                    "mean=${if (fecCalls == 0L) 0.0 else fecElements.toDouble() / fecCalls}"
            )
            out.appendLine("      size histogram 0..10 then 11+: ${fecSizeHistogram.joinToString(" ")}")
            val empty = fecSizeHistogram[0]
            val single = fecSizeHistogram[1]
            out.appendLine(
                "      EMPTY=$empty (${pct(empty, fecCalls)}) SINGLETON=$single (${pct(single, fecCalls)}) " +
                    "2+=${fecCalls - empty - single}"
            )
            out.appendLine("      concrete List classes (sampled 1/1024): $fecClassSamples")
            out.appendLine(
                "    anyIdentical: calls=$anyCalls steps=$anySteps length=$anyLength hits=$anyHits " +
                    "meanSteps=${if (anyCalls == 0L) 0.0 else anySteps.toDouble() / anyCalls}"
            )
            out.appendLine("      size histogram 0..10 then 11+: ${anySizeHistogram.joinToString(" ")}")
            out.appendLine(
                "      EMPTY=${anySizeHistogram[0]} (${pct(anySizeHistogram[0], anyCalls)}) " +
                    "SINGLETON=${anySizeHistogram[1]} (${pct(anySizeHistogram[1], anyCalls)})"
            )
        }
        if (amp > 0) {
            out.appendLine("  (WARM.32) iterator-vs-indexed AMPLIFIER, r=$amp")
            val n1 = maxOf(ampCalls, 1) * amp
            out.appendLine(
                "    forEachChild list position — calls=$ampCalls reps/arm=$n1"
            )
            out.appendLine(
                "      A iterator : $ampIterNanos ns, ${ampIterNanos.toDouble() / n1} ns per call " +
                    "sink=$ampIterSink (mod r = ${ampIterSink % amp})"
            )
            out.appendLine(
                "      B indexed  : $ampIdxNanos ns, ${ampIdxNanos.toDouble() / n1} ns per call " +
                    "sink=$ampIdxSink (mod r = ${ampIdxSink % amp})"
            )
            out.appendLine(
                "      EQUIVALENCE: sinks equal = ${ampIterSink == ampIdxSink}; " +
                    "A - B = ${(ampIterNanos - ampIdxNanos).toDouble() / n1} ns per call " +
                    "(single-r, NOT the answer — fit p(r)=cost+boundary/r per arm)"
            )
            val n2 = maxOf(ampAnyCalls, 1) * amp
            out.appendLine(
                "    anyIdentical — calls=$ampAnyCalls reps/arm=$n2"
            )
            out.appendLine(
                "      A iterator : $ampAnyIterNanos ns, ${ampAnyIterNanos.toDouble() / n2} ns per call " +
                    "sink=$ampAnyIterSink (mod r = ${ampAnyIterSink % amp})"
            )
            out.appendLine(
                "      B indexed  : $ampAnyIdxNanos ns, ${ampAnyIdxNanos.toDouble() / n2} ns per call " +
                    "sink=$ampAnyIdxSink (mod r = ${ampAnyIdxSink % amp})"
            )
            out.appendLine(
                "      EQUIVALENCE: sinks equal = ${ampAnyIterSink == ampAnyIdxSink}; " +
                    "A - B = ${(ampAnyIterNanos - ampAnyIdxNanos).toDouble() / n2} ns per call"
            )
        }
    }

    private fun pct(a: Long, b: Long): String =
        if (b == 0L) "0.0%" else "${(a * 1000 / b) / 10.0}%"
}
