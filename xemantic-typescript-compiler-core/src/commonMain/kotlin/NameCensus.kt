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
 * (WARM.24) round 897 — the price of round 894's candidate (1), *scanner
 * identifier interning*, taken BEFORE a line of fix.
 *
 * `docs/perf/warm-hash-owner-census.md` § 9(1) ranks interning first at an
 * upper bound of **67.7 ms/rebuild (1.24%)** — the whole in-map
 * `String.equals` (42.9) plus `String.hashCode` (24.8) leaf total — and says
 * in the same paragraph that the fix's own cost is **unpriced**: an intern
 * table is itself a hash probe per identifier TOKEN, and the token population
 * is far larger than any single map's. Round 788's law is the risk in one
 * sentence: *skipping a cached resolution MOVES the work, it does not delete
 * it.* This object exists to decide that with numbers instead of an argument.
 *
 * ## What it measures, and why each part needs its own shape
 *
 *  * **The token population** — how many identifier tokens the Scanner builds
 *    with `text.substring` (Scanner.kt:769), how many DISTINCT names they
 *    collapse onto, and the resulting hit rate an intern table would see. A
 *    high distinct count kills the idea outright; a high hit rate is what
 *    makes it affordable.
 *  * **The probe population** — how many times a name string is used to probe
 *    the two hottest `String`-keyed containers on the resolution path
 *    (`moduleOnlyGlobalNames`, a `HashSet<String>` probed as the very first
 *    statement of `lookupPerFileForNode`, and `globals`, a `SymbolTable`),
 *    split by whether the probe HITS. The split matters more than the total:
 *    `HashMap` calls `String.equals` only for an entry whose 32-bit hash
 *    matches, so a MISS essentially never walks characters and only a HIT can
 *    pay the cost interning removes.
 *  * **The recovery, by REPLAY** — round 896(B)'s shape. The captured probe
 *    sequence is replayed against the real member population twice: once with
 *    the production `String` instances (a probe and its stored key are
 *    different objects holding equal characters — exactly § 5a's 42.9 ms) and
 *    once with every string collapsed onto a canonical instance, so
 *    `String.equals` takes its `this == anObject` fast path. The two arms
 *    alternate order per rep (ABBA) so a drift lands on both. That difference
 *    IS what interning recovers, over the identical population, in one
 *    process — which no wall A/B at ~1% could show.
 *  * **The cost, by REPLAY on a FRESH table per rep** — the intern probe
 *    itself, over the captured token sequence. A fresh table per rep is not
 *    fastidiousness: re-interning into one warm table would measure a pure
 *    hit stream and omit every insert and every table growth the first parse
 *    of a program actually pays.
 *
 * ## The regime fact that decides how the cost is read
 *
 * [CrawlParseCache] serves the program's parse from the previous request when
 * the bytes match, and `RealLibSnapshots` has done the same for lib files
 * since M2.1(c). **So in the WARM regime this arc measures, the Scanner does
 * not run at all**: the intern probe is paid once per file VERSION, while the
 * map probes it would make cheaper are paid on every rebuild. The token
 * counters here read zero on a cached rebuild, which is why the `namecensus`
 * tier disables the parse cache for its one rebuild — otherwise the
 * population the cost arm needs does not exist to be captured.
 *
 * ## One disclosed imprecision, in the SCANNER counters only
 *
 * The crawl parses concurrently on `Dispatchers.Default`, so [idToken] is
 * called from N workers and its counters, its distinct set and its capture are
 * a data race — measured, two processes on one binary disagree by ~3% on
 * `idTokens` and lose ~2-5% of the capture. That is disclosed rather than
 * fixed because no decision here turns on 3%, and because the alternative
 * (a per-thread accumulator in `commonMain`) would cost more than the answer.
 * **The PROBE counters are exact** — the checker is single-threaded, and the
 * two processes agree on them to the last digit, which is the control that
 * says the race is confined to where it is claimed to be.
 *
 * Off (`on == false`, `replayReps == 0` — the defaults) every hook is a static
 * read and a not-taken branch, as INV.0 requires.
 */
object NameCensus {

    /** Master switch for the counters and the captures. */
    var on: Boolean = false

    /** Reps of the replay arms; `0` = OFF. Run from the harness, after the rebuild. */
    var replayReps: Int = 0

    /**
     * Capture ceilings. A capture is an `ArrayList<String>` of references to
     * strings that already exist, so the memory is the list's own spine
     * (~8 bytes per entry) and not the characters; 4 M entries is ~32 MB per
     * capture, against the tsc profile's ~1.5 M tokens and ~2 M probes.
     */
    private const val CAP = 4_000_000

    // ---- the token population (Scanner) ---------------------------------

    /** Identifier-shaped tokens scanned, keyword or not — one `substring` each. */
    var idTokens: Long = 0

    /** Of those, the ones that turned out to be a reserved word. */
    var keywordTokens: Long = 0

    /** Total characters in those tokens, so a mean length can be quoted. */
    var idChars: Long = 0

    private val distinctNames = HashSet<String>()

    /** Distinct identifier VALUES — the size an intern table would reach. */
    val distinctNameCount: Int get() = distinctNames.size

    private val tokenCapture = ArrayList<String>()

    // ---- the probe population (Checker) ---------------------------------

    /** Entries to `lookupPerFileForNode` — one `moduleOnlyGlobalNames` probe each. */
    var nodeProbes: Long = 0

    /** Entries to `globalsForFile` — likewise one set probe each. */
    var gffProbes: Long = 0

    /** Of all set probes, the ones the set answered YES to. */
    var setHits: Long = 0

    /** Probes that went on to a `globals[name]` map read. */
    var globalReads: Long = 0

    private val probeCapture = ArrayList<String>()

    private var memberSnapshot: Array<String>? = null
    private var globalSnapshot: Array<String>? = null

    // ---- replay results --------------------------------------------------

    var repsRun: Long = 0
    var setRawNanos: Long = 0
    var setInternNanos: Long = 0
    var mapRawNanos: Long = 0
    var mapInternNanos: Long = 0
    var internNanos: Long = 0

    /** Per-rep hit counts — the ARITHMETIC falsifier (round 759), not a timing one. */
    var setRawHits: Long = 0
    var setInternHits: Long = 0
    var mapRawHits: Long = 0
    var mapInternHits: Long = 0
    var internHits: Long = 0

    /**
     * The FOLD arms — the design the naive intern arm above cannot see.
     *
     * `scanIdentifier` ALREADY probes a `String`-keyed map for every
     * identifier-shaped token (`KEYWORDS[word]`, Scanner.kt:772). So an intern
     * table need not be a SECOND probe: one map holding the ~160 reserved words
     * *and* every interned name answers both questions in one lookup, and the
     * marginal price of interning is then the difference between probing a
     * ~22.5 k-entry map and probing a ~160-entry one — not the whole probe.
     *
     * `keywordNanos` is the status quo (a fresh keyword-only map per rep),
     * `foldNanos` the folded table (seeded with the same keywords, growing by
     * one entry per new name). Both arms see the SAME token sequence, and the
     * replay's cached `String.hashCode` biases both identically, so their
     * DIFFERENCE is unaffected by it — which is exactly why this arm is
     * trustworthy where the absolute of the naive intern arm is not.
     */
    var keywordNanos: Long = 0
    var foldNanos: Long = 0
    var keywordHitsSeen: Long = 0
    var foldHitsSeen: Long = 0

    /** Consumes replayed results so nothing can be elided. */
    var sink: Long = 0

    fun reset() {
        idTokens = 0; keywordTokens = 0; idChars = 0
        distinctNames.clear(); tokenCapture.clear()
        nodeProbes = 0; gffProbes = 0; setHits = 0; globalReads = 0
        probeCapture.clear()
        memberSnapshot = null; globalSnapshot = null
        repsRun = 0
        setRawNanos = 0; setInternNanos = 0
        mapRawNanos = 0; mapInternNanos = 0; internNanos = 0
        setRawHits = 0; setInternHits = 0; mapRawHits = 0; mapInternHits = 0
        internHits = 0
        keywordNanos = 0; foldNanos = 0; keywordHitsSeen = 0; foldHitsSeen = 0
        sink = 0
    }

    /**
     * One identifier-shaped token, from the single `Scanner` site that mints a
     * name (`scanIdentifier`, plus its escape-carrying twin).
     */
    fun idToken(word: String, keyword: Boolean) {
        idTokens++
        idChars += word.length.toLong()
        // EVERY identifier-shaped token is captured, keyword or not: the FOLD arm
        // below models the probe the Scanner ALREADY performs (`KEYWORDS[word]`),
        // and that one is paid for a `return` exactly as for a name.
        if (tokenCapture.size < CAP) tokenCapture.add(word)
        if (keyword) { keywordTokens++; return }
        distinctNames.add(word)
    }

    /**
     * One name probe on the per-file resolution path, plus — on the first call
     * — a snapshot of the two containers the replay needs.
     *
     * The snapshot is taken HERE rather than at the sets' construction sites
     * because this is the only place that holds both, and because taking it at
     * first probe guarantees it is the populated state: `moduleOnlyGlobalNames`
     * is empty until init step 1b2, and a snapshot taken before that would
     * make every replayed probe a miss and the whole answer vacuous.
     */
    fun nameProbe(name: String, inSet: Boolean, node: Boolean) {
        if (node) nodeProbes++ else gffProbes++
        if (inSet) setHits++ else globalReads++
        if (probeCapture.size < CAP) probeCapture.add(name)
    }

    /**
     * Test seam: install the four populations directly, as a real compile's
     * hooks would have captured them.
     *
     * It exists because the replay's ONE load-bearing modelling decision — that
     * a probe canonicalises onto the CONTAINER's own instance, which is the only
     * world in which `String.equals` can take `this == anObject` at a hit — is
     * otherwise unreachable from a test: the hooks fill their captures only
     * from a whole-project compile, and a pin that cannot construct the
     * population cannot see the decision at all.
     */
    fun seed(
        tokens: List<String>,
        probes: List<String>,
        members: Set<String>,
        globalNames: Set<String>,
    ) {
        tokenCapture.clear(); tokenCapture.addAll(tokens)
        probeCapture.clear(); probeCapture.addAll(probes)
        memberSnapshot = members.toTypedArray()
        globalSnapshot = globalNames.toTypedArray()
        for (t in tokens) if (KEYWORDS[t] == null) distinctNames.add(t)
    }

    fun publish(members: Set<String>, globalNames: Set<String>) {
        if (memberSnapshot != null) return
        if (members.isEmpty()) return
        memberSnapshot = members.toTypedArray()
        globalSnapshot = globalNames.toTypedArray()
    }

    /**
     * The four timed arms, over the captured populations.
     *
     * Ordering is ABBA per rep within each pair, so a drift inside the run
     * lands on both arms of that pair rather than on whichever ran first —
     * the same discipline round 896's `replayFlowKeys` uses, and the reason a
     * single leading draw cannot set the slope (rounds 869/891).
     */
    fun replay() {
        val reps = replayReps
        if (reps <= 0) return
        val members = memberSnapshot ?: return
        val globalNames = globalSnapshot ?: return
        val probes = probeCapture.toTypedArray()
        val tokens = tokenCapture.toTypedArray()
        if (probes.isEmpty()) return

        // The canonical instance for every distinct VALUE seen anywhere. Members
        // and global names are entered FIRST, so a probe whose value is in a
        // container canonicalises onto that container's own instance — which is
        // precisely the world interning produces, and the only world in which
        // `String.equals` can take `this == anObject` at a hit.
        val canon = HashMap<String, String>()
        for (s in members) if (!canon.containsKey(s)) canon[s] = s
        for (s in globalNames) if (!canon.containsKey(s)) canon[s] = s
        for (s in probes) if (!canon.containsKey(s)) canon[s] = s

        val setRaw = HashSet<String>()
        for (s in members) setRaw.add(s)
        val setInt = HashSet<String>()
        for (s in members) setInt.add(canon[s]!!)
        val mapRaw = HashMap<String, Any>()
        for (s in globalNames) mapRaw[s] = Unit
        val mapInt = HashMap<String, Any>()
        for (s in globalNames) mapInt[canon[s]!!] = Unit
        val probesInt = Array(probes.size) { canon[probes[it]]!! }

        var rep = 0
        while (rep < reps) {
            repsRun++
            if (rep % 2 == 0) {
                setRawHits += probeSet(setRaw, probes, raw = true)
                setInternHits += probeSet(setInt, probesInt, raw = false)
                mapRawHits += probeMap(mapRaw, probes, raw = true)
                mapInternHits += probeMap(mapInt, probesInt, raw = false)
            } else {
                setInternHits += probeSet(setInt, probesInt, raw = false)
                setRawHits += probeSet(setRaw, probes, raw = true)
                mapInternHits += probeMap(mapInt, probesInt, raw = false)
                mapRawHits += probeMap(mapRaw, probes, raw = true)
            }
            if (tokens.isNotEmpty()) {
                if (rep % 2 == 0) {
                    internHits += internPass(tokens)
                    keywordHitsSeen += keywordPass(tokens)
                    foldHitsSeen += foldPass(tokens)
                } else {
                    foldHitsSeen += foldPass(tokens)
                    keywordHitsSeen += keywordPass(tokens)
                    internHits += internPass(tokens)
                }
            }
            rep++
        }
    }

    private fun probeSet(set: HashSet<String>, keys: Array<String>, raw: Boolean): Long {
        val t0 = PassTiming.nowNanos()
        var hits = 0L
        var i = 0
        while (i < keys.size) { if (keys[i] in set) hits++; i++ }
        val dt = PassTiming.nowNanos() - t0
        if (raw) setRawNanos += dt else setInternNanos += dt
        sink += hits
        return hits
    }

    private fun probeMap(map: HashMap<String, Any>, keys: Array<String>, raw: Boolean): Long {
        val t0 = PassTiming.nowNanos()
        var hits = 0L
        var i = 0
        while (i < keys.size) { if (map[keys[i]] != null) hits++; i++ }
        val dt = PassTiming.nowNanos() - t0
        if (raw) mapRawNanos += dt else mapInternNanos += dt
        sink += hits
        return hits
    }

    /**
     * The COST arm: one whole-program intern pass over the token sequence into
     * a FRESH table, so every insert and every table growth is paid exactly as
     * a first parse pays them.
     */
    private fun internPass(tokens: Array<String>): Long {
        val t0 = PassTiming.nowNanos()
        val table = HashMap<String, String>()
        var hits = 0L
        var i = 0
        while (i < tokens.size) {
            val s = tokens[i]
            val c = table[s]
            if (c == null) table[s] = s else hits++
            i++
        }
        internNanos += PassTiming.nowNanos() - t0
        sink += hits
        return hits
    }

    /** The STATUS QUO arm: the keyword-only probe `scanIdentifier` performs today. */
    private fun keywordPass(tokens: Array<String>): Long {
        val t0 = PassTiming.nowNanos()
        val table = HashMap<String, SyntaxKind>(KEYWORDS)
        var hits = 0L
        var i = 0
        while (i < tokens.size) { if (table[tokens[i]] != null) hits++; i++ }
        keywordNanos += PassTiming.nowNanos() - t0
        sink += hits
        return hits
    }

    /** The FOLDED arm: ONE table answering "reserved word?" and "canonical instance?". */
    private fun foldPass(tokens: Array<String>): Long {
        val t0 = PassTiming.nowNanos()
        val table = HashMap<String, Any>(KEYWORDS)
        var hits = 0L
        var i = 0
        while (i < tokens.size) {
            val s = tokens[i]
            val e = table[s]
            if (e == null) table[s] = s else hits++
            i++
        }
        foldNanos += PassTiming.nowNanos() - t0
        sink += hits
        return hits
    }

    private fun ms(n: Long): String {
        val v = n / 1_000_000.0
        return ((v * 100).toLong() / 100.0).toString()
    }

    private fun nsPer(n: Long, ops: Long): String {
        if (ops == 0L) return "-"
        val v = n.toDouble() / ops
        return ((v * 1000).toLong() / 1000.0).toString()
    }

    fun report(): String = buildString {
        appendLine("== (WARM.24) name-intern census — round 897 ==")
        appendLine(
            "tokens: idTokens=$idTokens keyword=$keywordTokens name=${idTokens - keywordTokens}" +
                " distinctNames=$distinctNameCount meanLen=" +
                nsPer(idChars, if (idTokens == 0L) 0 else idTokens) +
                " captured=${tokenCapture.size}"
        )
        val nameTokens = idTokens - keywordTokens
        if (nameTokens > 0) {
            val hitRate = (nameTokens - distinctNameCount).toDouble() / nameTokens
            appendLine("  intern hit rate = ${((hitRate * 10000).toLong() / 100.0)}%")
        }
        appendLine(
            "probes: lookupPerFileForNode=$nodeProbes globalsForFile=$gffProbes" +
                " setHits=$setHits globalReads=$globalReads captured=${probeCapture.size}"
        )
        appendLine(
            "populations: moduleOnlyGlobalNames=${memberSnapshot?.size ?: 0}" +
                " globals=${globalSnapshot?.size ?: 0}"
        )
        if (repsRun > 0) {
            val n = repsRun
            appendLine("replay: reps=$n over ${probeCapture.size} probes / ${tokenCapture.size} tokens")
            appendLine(
                "  set   raw=${ms(setRawNanos / n)}ms intern=${ms(setInternNanos / n)}ms" +
                    " delta=${ms((setRawNanos - setInternNanos) / n)}ms" +
                    " perProbe raw=${nsPer(setRawNanos, probeCapture.size.toLong() * n)}ns" +
                    " intern=${nsPer(setInternNanos, probeCapture.size.toLong() * n)}ns"
            )
            appendLine(
                "  map   raw=${ms(mapRawNanos / n)}ms intern=${ms(mapInternNanos / n)}ms" +
                    " delta=${ms((mapRawNanos - mapInternNanos) / n)}ms" +
                    " perProbe raw=${nsPer(mapRawNanos, probeCapture.size.toLong() * n)}ns" +
                    " intern=${nsPer(mapInternNanos, probeCapture.size.toLong() * n)}ns"
            )
            appendLine(
                "  intern cost=${ms(internNanos / n)}ms per whole-program pass," +
                    " perToken=${nsPer(internNanos, tokenCapture.size.toLong() * n)}ns"
            )
            appendLine(
                "  FOLD  keywordOnly=${ms(keywordNanos / n)}ms folded=${ms(foldNanos / n)}ms" +
                    " marginal=${ms((foldNanos - keywordNanos) / n)}ms" +
                    " perToken keywordOnly=${nsPer(keywordNanos, tokenCapture.size.toLong() * n)}ns" +
                    " folded=${nsPer(foldNanos, tokenCapture.size.toLong() * n)}ns" +
                    " marginal=${nsPer(foldNanos - keywordNanos, tokenCapture.size.toLong() * n)}ns" +
                    " (hits/rep kw=${if (n == 0L) 0 else keywordHitsSeen / n}" +
                    " fold=${if (n == 0L) 0 else foldHitsSeen / n})"
            )
            appendLine(
                "  ARITHMETIC falsifier — hits must be an exact multiple of reps:" +
                    " setRaw=$setRawHits setIntern=$setInternHits" +
                    " mapRaw=$mapRawHits mapIntern=$mapInternHits intern=$internHits" +
                    " (per rep: ${setRawHits / n} / ${setInternHits / n} /" +
                    " ${mapRawHits / n} / ${mapInternHits / n} /" +
                    " ${if (tokenCapture.isEmpty()) 0 else internHits / n})"
            )
            appendLine("  sink=$sink")
        }
    }
}
