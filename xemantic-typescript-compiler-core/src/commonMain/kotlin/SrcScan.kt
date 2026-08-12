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
 * (WARM.19) round 895 — the `indexOf` sibling of the whole-program **regex**
 * census, and the gate that makes it cheap.
 *
 * ## What this is for
 *
 * Rounds 859/862/863 replaced whole-program *regexes* with "an EXACT
 * hand-written scan anchored on a literal via `indexOf`". Round 894's leaf-owner
 * census then found the post-fix form costing **116 ms per warm rebuild
 * (2.1%)**, spread over ~50 `Checker.check*` pin walkers of which the largest is
 * 0.16% — invisible row by row, invisible to `cost_gate.py`, and never counted.
 *
 * The shape is always the same: a walker loops over every `binderResult`, takes
 * `result.sourceFile.text`, and asks whether some corpus-unique literal occurs
 * in it. On tsc's own sources essentially none of them ever match, so the
 * program pays a full memory-bandwidth pass per (site x file) to learn nothing.
 *
 * ## The mechanism: an n-gram presence filter, one pass, many questions
 *
 * [SourceScanFilter] is built ONCE per source text and answers
 * "**definitely absent**" for any needle in time proportional to the NEEDLE,
 * not to the text. It records, in a bitset, the hash of every [K]-character
 * window of the text. A needle can only occur if *every* one of its own
 * [K]-windows occurs, so if any needle window's bit is clear the needle is
 * absent.
 *
 * **FALSE NEGATIVES ARE IMPOSSIBLE BY CONSTRUCTION, and the argument is one
 * line:** if `needle` occurs in `text` at position `p`, then for every `j` the
 * window `needle[j until j+K]` equals the window `text[p+j until p+j+K]`, which
 * the build visited and whose bit it therefore set. [SourceScanFilter.mayContain]
 * only ever returns `false` when some window's bit is CLEAR, so it cannot return
 * `false` for a needle that occurs. The hash may collide, and the 7-bit
 * character folding may collide; both make windows look PRESENT that are not,
 * i.e. both can only produce false POSITIVES. A false positive costs one real
 * scan and nothing else, because **the real `indexOf` remains the oracle** — the
 * filter is only ever consulted to skip a call, never to answer one.
 *
 * ## The obligations a change here inherits
 *
 * - [hashOf] and the build loop must fold characters IDENTICALLY. They are one
 *   expression written twice; if they ever diverge the filter starts answering
 *   "absent" for present needles and diagnostics vanish silently. `SrcScanTest`
 *   pins the equality directly, and [SrcScan.verify] runs both arms over a whole
 *   compile and counts divergences.
 * - A needle SHORTER than [K] cannot be filtered and must fall through
 *   ([SourceScanFilter.mayContain] returns `true` for it).
 * - The filter is a function of the text alone. Anything that makes it depend on
 *   the QUERY (e.g. remembering the last needle) breaks the amortisation this
 *   whole mechanism exists for.
 *
 * ## Why the cache is keyed by identity and not by the string
 *
 * A `HashMap<String, …>` would hash the file text — ~10 M characters over the
 * compiler profile, once per file, for nothing. [SrcScanCache] is a small
 * open-addressed table keyed on the text's LENGTH and probed with `===`. A miss
 * is never wrong, only slower: it rebuilds. That also keeps the round-471
 * hazard away — no `hashCode()` of anything large is ever taken.
 */
internal object SrcScan {

    /**
     * `--srcScanFilterOff`: restore the pre-895 path — every call goes straight
     * to `indexOf` with no filter and no filter build. The OFF arm lives in the
     * same binary so an equivalence pin and any A/B are a controlled row
     * (round 795), and it is the switch `SrcScanEquivalenceTest` ablates.
     */
    var off: Boolean = false

    /**
     * `--srcScanCensus`: count and TIME both mechanisms. Unlike most probes in
     * this repo the timestamp pair is affordable here — a whole-source scan is
     * tens of microseconds against a ~90 ns pair (rounds 734/735), so the
     * boundary is well under 1% and the nanos are quotable rather than merely
     * relative.
     */
    var on: Boolean = false

    /**
     * `--verifySrcScan`: run the filter AND the real scan at every call and
     * count the cases where the filter said "absent" and the scan disagreed.
     * A divergence is a soundness failure.
     *
     * Its POSITIVE CONTROL is [bogus] — a verifier that reads 0 both when the
     * gate is sound and when the instrument is dead is worthless (round 790).
     */
    var verify: Boolean = false

    /**
     * `--srcScanBogus`: the positive control. Corrupts the filter build so that
     * it records only every SECOND window, which makes [SourceScanFilter] refuse
     * needles that are genuinely present. Under `--verifySrcScan` the divergence
     * counter must then be NON-zero; if it still reads zero the verifier is
     * blind, not the gate sound.
     */
    var bogus: Boolean = false

    // -- census counters (deterministic; safe to assert on) -------------------

    /** Calls routed through the helpers, i.e. the whole population. */
    var calls: Long = 0

    /** Sum of `source.length` over [calls] — the text a pre-895 binary scanned. */
    var callChars: Long = 0

    /** Calls whose needle was actually found. */
    var found: Long = 0

    /** Calls the filter refused without scanning. */
    var refused: Long = 0

    /** Sum of `source.length` over [refused] — the characters NOT scanned. */
    var refusedChars: Long = 0

    /**
     * Calls that reached the real `indexOf` (either unfilterable or a maybe).
     *
     * **The partition is `refused + scanned == calls`.** [tooShort] is a SUBSET
     * of this, not a third bucket — a needle below [SourceScanFilter.K] cannot
     * be filtered, so it falls straight through and IS scanned. Getting that
     * wrong is how a census reports a population it never had; the partition is
     * asserted in `SrcScanTest`, which is what caught it.
     */
    var scanned: Long = 0

    /** Sum of `source.length` over [scanned]. */
    var scannedChars: Long = 0

    /** Calls whose needle was shorter than [SourceScanFilter.K] — a SUBSET of [scanned]. */
    var tooShort: Long = 0

    /** Filters built. */
    var builds: Long = 0

    /** Characters walked by the builds. */
    var buildChars: Long = 0

    /** Nanos inside the real `indexOf` calls (census mode only). */
    var scanNanos: Long = 0

    /** Nanos inside the filter builds (census mode only). */
    var buildNanos: Long = 0

    /** `--verifySrcScan` divergences: filter said absent, the scan found it. */
    var divergences: Long = 0

    /** `--verifySrcScan` calls compared. */
    var verified: Long = 0

    fun reset() {
        calls = 0; callChars = 0; found = 0
        refused = 0; refusedChars = 0
        scanned = 0; scannedChars = 0; tooShort = 0
        builds = 0; buildChars = 0
        scanNanos = 0; buildNanos = 0
        divergences = 0; verified = 0
    }

    fun report(): String {
        val b = StringBuilder()
        b.append("== (WARM.19) whole-source scan census ==\n")
        b.append("  arm                 ").append(if (off) "OFF (pre-895: no filter)" else "ON").append('\n')
        b.append("  calls               ").append(calls).append('\n')
        b.append("  callChars           ").append(callChars).append('\n')
        b.append("  found               ").append(found).append('\n')
        b.append("  refused             ").append(refused).append('\n')
        b.append("  refusedChars        ").append(refusedChars).append('\n')
        b.append("  scanned             ").append(scanned).append('\n')
        b.append("  scannedChars        ").append(scannedChars).append('\n')
        b.append("  tooShort            ").append(tooShort).append('\n')
        b.append("  builds              ").append(builds).append('\n')
        b.append("  buildChars          ").append(buildChars).append('\n')
        b.append("  scanNanos           ").append(scanNanos)
            .append("  (").append(scanNanos / 1_000_000).append(" ms)\n")
        b.append("  buildNanos          ").append(buildNanos)
            .append("  (").append(buildNanos / 1_000_000).append(" ms)\n")
        if (verify) {
            b.append("  verified            ").append(verified).append('\n')
            b.append("  divergences         ").append(divergences).append('\n')
        }
        return b.toString()
    }
}

/**
 * A per-source-text presence filter over fixed-width character windows.
 *
 * See [SrcScan] for the soundness argument, which is the whole point of this
 * class: [mayContain] returning `false` is a PROOF of absence; returning `true`
 * is no claim at all.
 */
internal class SourceScanFilter(text: String) {

    private val bits: LongArray
    private val mask: Int

    init {
        // One bit per window, sized so the table stays a few tens of kilobytes
        // even for the profile's largest file (checker.ts, ~3.15 M chars): a
        // fuller table costs only false POSITIVES, and a false positive costs
        // one scan that a pre-895 binary would have run anyway.
        val windows = if (text.length < K) 0 else text.length - K + 1
        var wordCount = 1 shl (MIN_WORDS_LOG2 - 6)
        while (wordCount < (windows shr 3) && wordCount < (1 shl (MAX_BITS_LOG2 - 6))) {
            wordCount = wordCount shl 1
        }
        bits = LongArray(wordCount)
        mask = (wordCount shl 6) - 1
        // The build. Kept as a rolling fold so the per-character cost is a
        // shift, an or, an and and one bit-set — the same fold `hashOf` applies
        // to a needle, which is the invariant `SrcScanTest` pins.
        if (windows > 0) {
            var h = 0
            var i = 0
            val step = if (SrcScan.bogus) 2 else 1
            while (i < K - 1) {
                h = ((h shl SHIFT) or (text[i].code and CHAR_MASK)) and WINDOW_MASK
                i++
            }
            var w = 0
            while (i < text.length) {
                h = ((h shl SHIFT) or (text[i].code and CHAR_MASK)) and WINDOW_MASK
                // `step != 1` is the --srcScanBogus positive control: it drops
                // half the windows, which makes `mayContain` answer `false` for
                // needles that ARE present. Production always records every one.
                if (step == 1 || (w and 1) == 0) {
                    val b = spread(h) and mask
                    bits[b ushr 6] = bits[b ushr 6] or (1L shl (b and 63))
                }
                w++
                i++
            }
        }
    }

    /**
     * `false` means [needle] is CERTAINLY absent from the text this was built
     * from. `true` means nothing.
     */
    fun mayContain(needle: String): Boolean {
        if (needle.length < K) return true
        var h = 0
        var i = 0
        while (i < K - 1) {
            h = ((h shl SHIFT) or (needle[i].code and CHAR_MASK)) and WINDOW_MASK
            i++
        }
        while (i < needle.length) {
            h = ((h shl SHIFT) or (needle[i].code and CHAR_MASK)) and WINDOW_MASK
            val b = spread(h) and mask
            if ((bits[b ushr 6] ushr (b and 63)) and 1L == 0L) return false
            i++
        }
        return true
    }

    internal companion object {

        /**
         * Window width. Four is the smallest width at which a needle window is
         * specific enough to be rare in source text, and it keeps the fold to
         * 28 bits (see [WINDOW_MASK]) so every window of ASCII text is a
         * DISTINCT integer before [spread] — the folding loses information only
         * for characters above 127.
         */
        const val K = 4

        /** Bits of each character kept by the fold. */
        const val SHIFT = 7
        const val CHAR_MASK = 0x7F
        const val WINDOW_MASK = (1 shl (SHIFT * K)) - 1

        /** Table floor: 8 Kbit = 1 KB, for the many small files. */
        const val MIN_WORDS_LOG2 = 13

        /** Table ceiling: 512 Kbit = 64 KB, which stays comfortably in L2. */
        const val MAX_BITS_LOG2 = 19

        /**
         * Fibonacci spread. The fold packs windows into the LOW bits and
         * consecutive windows of ordinary text land in neighbouring slots; an
         * odd-constant multiply is a bijection mod 2^32 (CLAUDE.md's
         * `packIdPair` entry) so it moves them apart without ever mapping two
         * distinct windows onto one another before the mask is applied.
         */
        fun spread(h: Int): Int = (h * -0x61c88647) ushr 8

        /** The window fold, as a needle sees it. Pinned equal to the build. */
        fun hashOf(s: String, from: Int): Int {
            var h = 0
            for (i in from until from + K) {
                h = ((h shl SHIFT) or (s[i].code and CHAR_MASK)) and WINDOW_MASK
            }
            return h
        }
    }
}

/**
 * An identity-probed, length-keyed cache of [SourceScanFilter]s.
 *
 * Deliberately NOT a `HashMap<String, …>`: hashing the key would walk the whole
 * file text once per file for nothing. A slot holds a `(text, filter)` pair and
 * is matched with `===`; a length collision between two different texts simply
 * probes on, and a full table evicts. **An eviction is never wrong** — the next
 * query rebuilds — so nothing here can change an answer, only a cost.
 *
 * One instance per `Checker`, so a `--workers` run has no shared mutable state
 * (CLAUDE.md: a plain `getOrPut` from N worker threads is a data race with no
 * exception to find it by).
 */
internal class SrcScanCache {

    private var keys = arrayOfNulls<String>(CAPACITY)
    private var vals = arrayOfNulls<SourceScanFilter>(CAPACITY)

    fun filterFor(text: String): SourceScanFilter {
        var i = slot(text.length)
        var probes = 0
        while (probes < PROBES) {
            val k = keys[i]
            if (k == null) break
            if (k === text) return vals[i]!!
            i = (i + 1) and (CAPACITY - 1)
            probes++
        }
        val built = build(text)
        keys[i] = text
        vals[i] = built
        return built
    }

    private fun build(text: String): SourceScanFilter {
        if (!SrcScan.on) {
            SrcScan.builds++
            SrcScan.buildChars += text.length
            return SourceScanFilter(text)
        }
        val t0 = PassTiming.nowNanos()
        val f = SourceScanFilter(text)
        SrcScan.buildNanos += PassTiming.nowNanos() - t0
        SrcScan.builds++
        SrcScan.buildChars += text.length
        return f
    }

    private fun slot(length: Int): Int = ((length * -0x61c88647) ushr 20) and (CAPACITY - 1)

    private companion object {
        /** 1,024 slots — every profile here is well under that many files. */
        const val CAPACITY = 1024

        /** Probe budget before an eviction; an eviction is correct, only slower. */
        const val PROBES = 8
    }
}
