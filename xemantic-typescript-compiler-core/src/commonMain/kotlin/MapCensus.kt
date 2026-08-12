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

    // ---- (WARM.27)(1) resolveImportedSymbolGeneral -------------------------

    /**
     * Round 899 § 33.8(1) ranked `resolveImportedSymbolGeneral` at **24.3 ms**, of
     * which **21.9 ms is `HashMap.containsKey`** on `importedSymbolGeneralCache` —
     * a `containsKey`-then-`get` DOUBLE PROBE (round 896's `globalsForFile` shape)
     * on an `Int`-keyed, therefore BOXED, cache.
     *
     * The row is physically real only at **~0.7-1.5 M probes per rebuild** (an
     * `Integer`-keyed probe is ~15-30 ns), and that population was UNKNOWN, so
     * round 898's admission test says the first instrument is a counter and not a
     * fix. [risgTopLevel] is the probe population (one `containsKey` each) and
     * [risgHits] the second probe (the `get` a hit additionally pays), so the
     * removable half is exactly [risgHits] probes plus their boxing.
     *
     * The hook sits at the function's own entry, NOT on the cache guard: round
     * 849's trap is a hook on an inner test that the caller duplicates, and here
     * the caller (`lookupInFileScope`) pre-filters on the SYMBOL's alias flags —
     * a different predicate — so entry is the boundary that sees every call.
     */
    var risgCalls: Long = 0

    /** Calls made at the top level, i.e. those that probe the cache. */
    var risgTopLevel: Long = 0

    /** Top-level calls the cache answered — the second probe of the double probe. */
    var risgHits: Long = 0

    fun risgEnter(topLevel: Boolean, hit: Boolean) {
        risgCalls++
        if (!topLevel) return
        risgTopLevel++
        if (hit) risgHits++
    }

    // ---- (WARM.28)(2) lexLevelHasName / lexLevelHasType --------------------

    /**
     * Round 899 § 33.8(2) ranked `lexLevelHasName` at **30.1 ms**, 100% lookup, of
     * which 29.8 ms is `HashMap.containsKey`, and explicitly did NOT refute it: at
     * ~30 ns a `String`-keyed probe that implies ~1.0 M probes per rebuild, which
     * is plausible beside `globals.lookups` 748,522. The proposed lever is a
     * proof-of-absence filter per lexical level (round 895's `SourceScanFilter`
     * shape), so the population that decides it is **how many probes land on a
     * map that can actually cost something**.
     *
     * That last clause is the reason these counters split EMPTY from non-empty.
     * `HashMap.getNode` reads `table` BEFORE it hashes the key, and a
     * `mutableMapOf()` that was never written keeps `table == null` — so a probe
     * of an empty level is a null check and a return, and no filter can be faster
     * than that. A census that counted probes alone would price the empty ones at
     * the 20-50 ns reference rate and manufacture a prize.
     *
     * [lexScopeKeys] is the other half of the decision: a filter has to be BUILT,
     * once per queried scope, at one hash per key, so the build is only affordable
     * while the keys are far fewer than the probes they refuse.
     */
    var lexCalls: Long = 0

    /** Refused by the untrusted-owner rule (ModuleDeclaration / EnumDeclaration). */
    var lexUntrusted: Long = 0

    /** Refused by the non-head function-like rule (INV.4(c)(ii) rule 1). */
    var lexFnSkipped: Long = 0

    /** `symbols` probes that reached a map with a null table — a hash-free return. */
    var lexSymEmpty: Long = 0

    /** `symbols` probes that reached a NON-empty map, i.e. cost a real hash. */
    var lexSymProbe: Long = 0

    var lexSymHit: Long = 0

    /** `existing` probes reached (only the SourceFile root has one — see Binder). */
    var lexExProbe: Long = 0

    var lexExHit: Long = 0

    /** Refused by [Checker] `unresolvedLexRootExcluded` before the `existing` probe. */
    var lexRootExcluded: Long = 0

    /** Calls that probed at least one NON-empty map and answered false. */
    var lexAbsent: Long = 0

    /** Calls whose `symbols` missed and whose level carries no `existing` table. */
    var lexNoExisting: Long = 0

    /**
     * …of which the `symbols` map was NON-empty, i.e. the probe really cost
     * something. This is the population a proof-of-absence filter could refuse
     * outright, and with [lexAbsent] + [lexAbsentReal] it is the whole prize.
     */
    var lexNoExistingReal: Long = 0

    /** `existing` misses whose preceding `symbols` probe was also a real one. */
    var lexAbsentReal: Long = 0

    // `lexLevelHasType`, the same shape one map operation over.
    var lexTypeCalls: Long = 0
    var lexTypeSymEmpty: Long = 0
    var lexTypeSymProbe: Long = 0
    var lexTypeExProbe: Long = 0
    var lexTypeAbsent: Long = 0

    /** `l.symbols[name]` probes made by `isTypeParam` / `typeParamConstraintOf`. */
    var lexTpEmpty: Long = 0
    var lexTpProbe: Long = 0

    /** Every [LexicalScope] the binder produces, and their own-symbol keys. */
    var lexScopesBound: Long = 0
    var lexScopeBoundKeys: Long = 0

    fun lexBound(scopes: Map<Int, LexicalScope>) {
        for ((_, s) in scopes) {
            lexScopesBound++
            lexScopeBoundKeys += s.symbols.size.toLong()
        }
    }

    /** Distinct [LexicalScope]s any of the three families ever probed. */
    private val lexScopes = HashSet<LexicalScope>()

    var lexScopesQueried: Long = 0

    /** Keys a per-scope filter would have to hash, summed over [lexScopesQueried]. */
    var lexScopeKeys: Long = 0

    /** …of which live in an `existing` table (the SourceFile root's file locals). */
    var lexScopeExistingKeys: Long = 0

    /**
     * Probes performed under ONE timestamp pair at a real `l.symbols` probe site;
     * `0` = OFF. `--lexLevelAmp N`.
     *
     * Round 899 § 33.8(2) priced this candidate at the arc's generic "~30 ns per
     * `String`-keyed `containsKey`" reference. That reference was measured on
     * `perFileScope`, whose keys are file PATHS, in a populated table — and the
     * census says the mean queried lexical level holds **1.5** own symbols. A
     * probe of a 1-2 entry map is the cheapest probe there is, so the generic band
     * does not transfer and the row's implied rate has to be measured, not
     * assumed (round 789: a cost prior from one family of sites does not transfer
     * to another).
     *
     * Both arms are amplified — the real map probe AND the 64-bit filter test the
     * lever would replace it with — because what a decision needs is what a swap
     * RECOVERS, not what the old container costs ([replayFlowKeys]'s law one
     * candidate over). At equal `r` the ~90 ns boundary cancels BETWEEN the arms,
     * and two values of `r` cancel it algebraically within each.
     */
    var lexLevelAmp: Int = 0

    var lexAmpCalls: Long = 0
    var lexAmpMapNanos: Long = 0
    var lexAmpFilterNanos: Long = 0

    /** Filter masks, one per scope, built once — what production would cache. */
    private val lexMasks = HashMap<LexicalScope, Long>()

    /**
     * A 64-bit proof-of-absence mask over a level's own `symbols` keys: one clear
     * bit is a proof of absence, a set bit sends the query to the real map, which
     * stays the oracle (round 895's `SourceScanFilter` shape).
     */
    private fun lexMaskOf(l: LexicalScope): Long = lexMasks.getOrPut(l) {
        var m = 0L
        for (k in l.symbols.keys) m = m or (1L shl (k.hashCode() and 63))
        m
    }

    /**
     * Amplifies one real `symbols` probe in both arms, ABBA per call so a drift
     * inside the file lands on both. [sink] is the arithmetic falsifier: it must
     * come out an exact multiple of `r` (round 759), or the JIT elided a loop.
     */
    fun lexAmp(l: LexicalScope, name: String) {
        val r = lexLevelAmp
        if (r <= 0) return
        val mask = lexMaskOf(l)
        lexAmpCalls++
        if (lexAmpCalls and 1L == 0L) { lexAmpMap(l, name, r); lexAmpFilter(mask, name, r) }
        else { lexAmpFilter(mask, name, r); lexAmpMap(l, name, r) }
    }

    private fun lexAmpMap(l: LexicalScope, name: String, r: Int) {
        val t0 = PassTiming.nowNanos()
        var seen = 0L
        var i = 0
        while (i < r) { if (l.symbols.containsKey(name)) seen++; i++ }
        lexAmpMapNanos += PassTiming.nowNanos() - t0
        sink += seen
    }

    private fun lexAmpFilter(mask: Long, name: String, r: Int) {
        val t0 = PassTiming.nowNanos()
        var seen = 0L
        var i = 0
        while (i < r) { if ((mask ushr (name.hashCode() and 63)) and 1L != 0L) seen++; i++ }
        lexAmpFilterNanos += PassTiming.nowNanos() - t0
        sink += seen
    }

    /**
     * Records one queried scope's size ONCE. Identity-keyed, which is sound because
     * [LexicalScope] is a plain class and not a `data class` (round 471 / round 865).
     */
    fun lexScope(l: LexicalScope) {
        if (!lexScopes.add(l)) return
        lexScopesQueried++
        lexScopeKeys += l.symbols.size.toLong()
        val ex = l.existing
        if (ex != null) {
            lexScopeKeys += ex.size.toLong()
            lexScopeExistingKeys += ex.size.toLong()
        }
    }

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
        risgCalls = 0; risgTopLevel = 0; risgHits = 0
        lexCalls = 0; lexUntrusted = 0; lexFnSkipped = 0
        lexSymEmpty = 0; lexSymProbe = 0; lexSymHit = 0
        lexExProbe = 0; lexExHit = 0; lexRootExcluded = 0
        lexAbsent = 0; lexNoExisting = 0; lexNoExistingReal = 0; lexAbsentReal = 0
        lexTypeCalls = 0; lexTypeSymEmpty = 0; lexTypeSymProbe = 0
        lexTypeExProbe = 0; lexTypeAbsent = 0
        lexTpEmpty = 0; lexTpProbe = 0
        lexScopes.clear(); lexScopesQueried = 0; lexScopeKeys = 0; lexScopeExistingKeys = 0
        lexMasks.clear(); lexAmpCalls = 0; lexAmpMapNanos = 0; lexAmpFilterNanos = 0
        lexScopesBound = 0; lexScopeBoundKeys = 0
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
        appendLine(
            "  (WARM.27)(1) resolveImportedSymbolGeneral: calls=$risgCalls " +
                "top-level=$risgTopLevel hits=$risgHits " +
                "map probes=${risgTopLevel + risgHits} (containsKey $risgTopLevel + get $risgHits)"
        )
        appendLine(
            "  (WARM.28)(2) lexLevelHasName: calls=$lexCalls untrusted=$lexUntrusted " +
                "fnSkipped=$lexFnSkipped rootExcluded=$lexRootExcluded"
        )
        appendLine(
            "       symbols: EMPTY(no table, hash-free)=$lexSymEmpty  REAL probes=$lexSymProbe " +
                "hits=$lexSymHit   existing: probes=$lexExProbe hits=$lexExHit"
        )
        appendLine(
            "       existing probed and MISSED=$lexAbsent (of which after a real symbols probe=$lexAbsentReal)" +
                "   symbols missed with no existing table=$lexNoExisting (real=$lexNoExistingReal)"
        )
        appendLine(
            "       => REAL probes a proof-of-absence filter could refuse = " +
                "${lexNoExistingReal + lexAbsent + lexAbsentReal} " +
                "(of ${lexSymProbe + lexExProbe} real probes; filter would be TESTED on $lexSymProbe calls)"
        )
        appendLine(
            "  (WARM.28)(2) lexLevelHasType: calls=$lexTypeCalls EMPTY=$lexTypeSymEmpty " +
                "REAL=$lexTypeSymProbe existing<=$lexTypeExProbe absent=$lexTypeAbsent"
        )
        appendLine(
            "  (WARM.28)(2) isTypeParam/constraintOf symbols[]: EMPTY=$lexTpEmpty REAL=$lexTpProbe"
        )
        appendLine(
            "       scopes queried=$lexScopesQueried keys a filter would hash=$lexScopeKeys " +
                "(of which existing=$lexScopeExistingKeys) => mean own symbols per queried scope " +
                "${(lexScopeKeys - lexScopeExistingKeys).toDouble() / maxOf(lexScopesQueried, 1)}"
        )
        appendLine(
            "       scopes BOUND=$lexScopesBound holding $lexScopeBoundKeys own keys " +
                "— what an EAGER (race-free) filter must build, against $lexScopesQueried queried"
        )
        if (lexAmpCalls > 0) {
            appendLine(
                "       amplified r=$lexLevelAmp over $lexAmpCalls calls:  " +
                    "MAP p(r)=${lexAmpMapNanos / lexAmpCalls} ns   " +
                    "FILTER p(r)=${lexAmpFilterNanos / lexAmpCalls} ns   " +
                    "delta=${(lexAmpMapNanos - lexAmpFilterNanos) / lexAmpCalls} ns   sink=$sink"
            )
            appendLine("       the boundary cancels BETWEEN the arms at equal r; two r give each slope")
        }
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
