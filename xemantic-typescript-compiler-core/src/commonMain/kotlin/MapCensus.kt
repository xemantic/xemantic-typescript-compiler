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

    /**
     * Bucketed by own-symbol count, because the SUCCESSOR candidate this census
     * uncovered — replacing a 1.5-entry `HashMap` per scope with a parallel-array
     * linear scan — lives or dies on the TAIL. A linear scan is faster than a
     * hash probe only up to some size, so what decides it is not the mean but how
     * much of the population sits above the fallback threshold.
     */
    val lexBoundHistogram = LongArray(10)

    fun lexBound(scopes: Map<Int, LexicalScope>) {
        for ((_, s) in scopes) {
            lexScopesBound++
            val n = s.symbols.size
            lexScopeBoundKeys += n.toLong()
            lexBoundHistogram[if (n >= 9) 9 else n]++
        }
    }

    /** Distinct [LexicalScope]s any of the three families ever probed. */
    private val lexScopes = HashSet<LexicalScope>()

    /**
     * (WARM.29) the queried scopes, for a pin that has to inspect the parallel
     * arrays the scan arm builds. Read-only and census-only — nothing in the
     * compiler reaches it.
     */
    fun lexScopesSeen(): Set<LexicalScope> = lexScopes

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

    /**
     * Per-arm sinks, split because one shared sink cannot tell a dropped arm from
     * a running one — and because SPLIT they pin the property the whole lever
     * would rest on: the filter is a SUPERSET of the map, so
     * [lexAmpFilterSink] >= [lexAmpMapSink] at every `r`. A mask that ever refused
     * a name the map holds would break that inequality, which is a proof of
     * absence failing, which is a suppressed resolution.
     */
    var lexAmpMapSink: Long = 0
    var lexAmpFilterSink: Long = 0

    /**
     * (WARM.29) the third arm: a parallel-array linear scan, the successor round 901
     * § 5 priced from the size histogram (46.7% of bound scopes hold ZERO own
     * symbols, 98.7% hold <= 8) but explicitly did NOT build, because its rate was
     * ESTIMATED at "~3-6 ns" and an estimate is not a measurement.
     *
     * Matched to the MAP arm's operation exactly — presence, not retrieval — so the
     * delta is a like-for-like `containsKey` swap. A production `get` would add one
     * more load from an array contiguous with the one just scanned.
     *
     * This arm measures a LOWER BOUND on the scan's real cost, and the bound is
     * stated rather than hidden: it reads a bare `Array<String>` field through a
     * direct load, i.e. it prices the ideal implementation with no container object
     * and no dispatch between the caller and the array. A production shape that put
     * the scan behind a `MutableMap` facade would pay an interface call on top.
     */
    var lexAmpScanNanos: Long = 0

    /**
     * Split per arm for round 901's reason (one shared sink cannot tell a dropped
     * arm from a running one) — and this one pins something stronger than the
     * filter's inequality: a scan over the same keys is EQUIVALENT to the map, so
     * [lexAmpScanSink] must equal [lexAmpMapSink] EXACTLY at every `r`. The filter
     * may only be a superset; the scan may not differ at all.
     */
    var lexAmpScanSink: Long = 0

    /**
     * (WARM.29) the size of the level a REAL probe lands on, bucketed and summed
     * over PROBES rather than over scopes.
     *
     * Round 901 § 5 priced the successor off `lexBoundHistogram`, which counts
     * SCOPES — 46.7% hold zero, 98.7% hold <= 8 — and concluded a linear scan
     * would serve the population at ~3-6 ns. That is round 890's law one family
     * over: **a scope population is not a probe population.** A level is scanned
     * once per probe, not once per existence, and the ascent reaches the outermost
     * levels on every walk, so a single large level can carry more scan steps than
     * the 15,270 empty ones save. Only this histogram can say which.
     */
    val lexProbeSizeHistogram = LongArray(10)

    /** Own symbols summed over REAL probes — the numerator of the scan's mean length. */
    var lexProbeSizeSum: Long = 0

    /** Element comparisons the scan arm performed, per rep, so `/ lexAmpCalls` is per probe. */
    var lexScanSteps: Long = 0

    /**
     * (WARM.29) the FOURTH arm: round 901 § 5's actual proposal, which was "a
     * parallel-array linear scan **(map fallback above ~8)**" — the unconditional
     * scan the third arm measures is its upper bound, not its shape.
     *
     * It has to be measured separately rather than derived from the third arm's
     * per-step rate, because a 3-element scan and a 212-element scan are different
     * memory behaviours (one cache line and up to three `String` dereferences
     * against a streamed array), and a per-step rate taken from the long one is a
     * cost prior that does not transfer (round 789).
     */
    var lexAmpHybridNanos: Long = 0
    var lexAmpHybridSink: Long = 0

    /** The scan/map split the hybrid actually took, so its arm cannot be read blind. */
    var lexHybridScanned: Long = 0
    var lexHybridFellBack: Long = 0

    /** Above this own-symbol count the hybrid arm defers to the real map. */
    const val LEX_HYBRID_MAX = 8

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
        val names = l.censusNames ?: l.symbols.keys.toTypedArray().also { l.censusNames = it }
        lexAmpCalls++
        // Probe-weighted, and recorded OUTSIDE every timestamp pair. The steps a
        // scan of THIS level would take: its whole length on a miss, the winning
        // index + 1 on a hit — which is what a linear scan actually pays and what
        // a scope-count histogram cannot express.
        lexProbeSizeSum += names.size.toLong()
        lexProbeSizeHistogram[if (names.size >= 9) 9 else names.size]++
        var st = 0
        while (st < names.size) { st++; if (names[st - 1] == name) break }
        lexScanSteps += st.toLong()
        // Three arms, so the two-position ABBA of round 901 becomes a three-phase
        // CYCLIC rotation: each arm runs first, second and last on a third of the
        // calls, which is what keeps a drift inside the file from landing on one of
        // them. The array is materialised ABOVE the rotation, outside every
        // timestamp pair, exactly as the mask is.
        if (names.size <= LEX_HYBRID_MAX) lexHybridScanned++ else lexHybridFellBack++
        when (lexAmpCalls and 3L) {
            0L -> {
                lexAmpMap(l, name, r); lexAmpFilter(mask, name, r)
                lexAmpScan(l, name, r); lexAmpHybrid(l, name, r)
            }
            1L -> {
                lexAmpFilter(mask, name, r); lexAmpScan(l, name, r)
                lexAmpHybrid(l, name, r); lexAmpMap(l, name, r)
            }
            2L -> {
                lexAmpScan(l, name, r); lexAmpHybrid(l, name, r)
                lexAmpMap(l, name, r); lexAmpFilter(mask, name, r)
            }
            else -> {
                lexAmpHybrid(l, name, r); lexAmpMap(l, name, r)
                lexAmpFilter(mask, name, r); lexAmpScan(l, name, r)
            }
        }
    }

    /**
     * Round 901 § 5's proposal as written: scan the array while the level is small,
     * defer to the real map above [LEX_HYBRID_MAX]. Equivalent to the map by
     * construction on BOTH branches, so [lexAmpHybridSink] must equal
     * [lexAmpMapSink] exactly — the same equality the pure scan carries, and the
     * one thing a threshold off by one would break silently.
     */
    private fun lexAmpHybrid(l: LexicalScope, name: String, r: Int) {
        val t0 = PassTiming.nowNanos()
        var seen = 0L
        var i = 0
        while (i < r) {
            val names = l.censusNames
            if (names != null && names.size <= LEX_HYBRID_MAX) {
                var j = 0
                while (j < names.size) {
                    if (names[j] == name) { seen++; break }
                    j++
                }
            } else if (l.symbols.containsKey(name)) seen++
            i++
        }
        lexAmpHybridNanos += PassTiming.nowNanos() - t0
        lexAmpHybridSink += seen
        sink += seen
    }

    /**
     * The successor's arm: a linear scan of the level's own names, reached from the
     * scope by one field load — no hash, no table, no `Node`.
     *
     * `seen` counts presence, matching [lexAmpMap]'s `containsKey` exactly, so
     * [lexAmpScanSink] == [lexAmpMapSink] is an equivalence assertion and not merely
     * a liveness one.
     */
    private fun lexAmpScan(l: LexicalScope, name: String, r: Int) {
        val t0 = PassTiming.nowNanos()
        var seen = 0L
        var i = 0
        while (i < r) {
            val names = l.censusNames
            if (names != null) {
                var j = 0
                while (j < names.size) {
                    if (names[j] == name) { seen++; break }
                    j++
                }
            }
            i++
        }
        lexAmpScanNanos += PassTiming.nowNanos() - t0
        lexAmpScanSink += seen
        sink += seen
    }

    private fun lexAmpMap(l: LexicalScope, name: String, r: Int) {
        val t0 = PassTiming.nowNanos()
        var seen = 0L
        var i = 0
        while (i < r) { if (l.symbols.containsKey(name)) seen++; i++ }
        lexAmpMapNanos += PassTiming.nowNanos() - t0
        lexAmpMapSink += seen
        sink += seen
    }

    private fun lexAmpFilter(mask: Long, name: String, r: Int) {
        val t0 = PassTiming.nowNanos()
        var seen = 0L
        var i = 0
        while (i < r) { if ((mask ushr (name.hashCode() and 63)) and 1L != 0L) seen++; i++ }
        lexAmpFilterNanos += PassTiming.nowNanos() - t0
        lexAmpFilterSink += seen
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

    // ---- (WARM.30) nodeTypes' deep AST-VALUE key ---------------------------

    /**
     * (WARM.30) round 903 — the census for `state.nodeTypes`, the
     * `HashMap<TypeNode, Type>` whose key is an AST **value**.
     *
     * Every concrete `TypeNode` is a `data class … : NodeBase()`, so the generated
     * `hashCode()`/`equals()` recurse the whole subtree — round 471's hazard,
     * living in the checker's own hottest resolution cache. `docs/perf` ranks it at
     * **57.1 ms**, the largest single map owner in a warm rebuild, and round 898's
     * admission test (divide the owner row by its own population) does NOT refute
     * it: 354,131 deep hashes per rebuild is 161 ns each, which is possible only if
     * the mean key subtree is large. **Nothing had measured that**, which is why
     * the first instrument is this census and not a re-key.
     *
     * Two properties of the numbers here are deliberate and a reader must carry
     * both:
     *
     *  * the subtree size is **PROBE-WEIGHTED** as well as object-weighted (round
     *    902's law: a scope population is not a probe population — transposed one
     *    family over, a NODE population is not a PROBE population, and a deep hash
     *    is paid once per probe, not once per node). [tnkSubtreeSum] divided by
     *    [tnkCalls] is what prices the row; [tnkObjectSubtreeSum] over
     *    [tnkObjects] is the shape of the cache and is reported beside it
     *    precisely so the two can be seen to disagree;
     *  * an UNINDEXED key (`nodeId == -1`, i.e. a `copy()`d or Transformer-
     *    synthesized node — INV.2(a)) is bucketed SEPARATELY rather than assumed
     *    absent, because a `(fileHash, nodeId)` successor key cannot address one
     *    at all and its population is the first thing such a re-key must survive.
     *
     * The size is a NODE count taken with [forEachChild], i.e. the number of
     * recursive `hashCode()` frames the key costs — not the number of scalar
     * fields each frame folds. It is a proxy and is stated as one.
     */
    var typeNodeKeyCensus: Boolean = false

    /**
     * Probes performed under ONE timestamp pair per arm at a real `nodeTypes`
     * probe site; `0` = OFF. `--typeNodeKeyAmp N`.
     *
     * Three arms, because the JFR row has **two owners** and a leaf-frame profile
     * cannot tell them apart (round 864's law, arriving before the measurement
     * instead of after it):
     *
     *  * **A** — the real `nodeTypes[node]` probe: deep hash, bucket, deep equals;
     *  * **B** — the same probe against a parallel [LongKeyMap] keyed on
     *    `(owning file hash, nodeId)`, populated in lockstep, so `A - B` is the
     *    deep-key PREMIUM rather than the whole probe;
     *  * **C** — `isPerFileDependentRefNode`, a recursive subtree walk that runs
     *    on EVERY call, cacheable or not, over the SAME subtree the hash walks,
     *    and is charged to the same owner by every leaf profile in this repo.
     *
     * `A - B` is an UPPER bound on what a re-key recovers: arm B's key is computed
     * OUTSIDE every timestamp pair, so it prices the ideal successor whose key is
     * free. A refusal taken against it is therefore a refusal with certainty; an
     * acceptance would still owe the key's own price.
     *
     * The arms rotate cyclically per call so none owns a bracket position, and
     * two values of `r` cancel the ~90 ns boundary algebraically within each arm.
     * The boundary also cancels BETWEEN arms at equal `r`, which is what makes a
     * FIRST-probe (i.e. production) rate readable at all (round 901).
     *
     * **The hoisting falsifier is the SLOPE, not the sink.** A pure function of an
     * immutable object may be hoisted out of the amplification loop by C2, and an
     * exact-multiple sink would still pass — which is why arm A amplifies the MAP
     * GET (a mutable escaping container C2 cannot prove pure) rather than
     * `node.hashCode()`, and why arm C is a RECURSIVE call (never inlined, hence
     * never hoisted). If any arm's `p(r)` is flat between two `r`, that arm was
     * elided and its number is not a measurement.
     */
    var typeNodeKeyAmp: Int = 0

    const val TNK_HIT = 0
    const val TNK_MISS = 1
    const val TNK_BYPASSED = 2

    /** Censused `getTypeFromTypeNodeCore` invocations. */
    var tnkCalls: Long = 0

    /** …answered by the cache — one deep hash and one deep `equals`. */
    var tnkHits: Long = 0

    /** …that missed a CACHEABLE probe — one hash here, three more below it. */
    var tnkMisses: Long = 0

    /** …refused by the cacheable gate, which still paid `isPerFileDependentRefNode`. */
    var tnkBypassed: Long = 0

    /** Probes whose key carries no `nodeId` — a `(file, nodeId)` re-key cannot address them. */
    var tnkUnindexed: Long = 0

    /** Subtree nodes summed over PROBES: the numerator of the deep hash's real length. */
    var tnkSubtreeSum: Long = 0

    /** The largest key subtree any probe presented. */
    var tnkMaxSubtree: Int = 0

    /** Probe-weighted subtree sizes, buckets 0..10 then 11+. */
    val tnkProbeSizeHistogram = LongArray(12)

    /** Distinct cache KEYS swept at the end of the check — the object-weighted arm. */
    var tnkObjects: Long = 0
    var tnkObjectSubtreeSum: Long = 0
    var tnkObjectUnindexed: Long = 0
    val tnkObjectSizeHistogram = LongArray(12)

    /** Entries the parallel `(file, nodeId)` map holds, against [tnkObjects]. */
    var tnkParallelEntries: Long = 0

    /** Stores performed into the parallel map — one per cacheable miss. */
    var tnkStores: Long = 0

    /**
     * (WARM.30) the REACHED control for amplifier arm C, and round 902's law made
     * cheap: an arm can be DEAD rather than the pin blind, and nothing else here
     * can tell the difference.
     *
     * `isPerFileDependentRefNode`'s FIRST line is
     * `if (multiFileModuleTypeNames.isEmpty() || depth > 4) return false`. On a
     * program with no type name declared in two module files that is a field
     * read and a return, so arm C would be pricing an `isEmpty()` check while
     * reading like a subtree walk — a number that is right for the profile and
     * wrong for the mechanism. This counter is what separates the two, and it is
     * recorded from the checker's own live set rather than re-derived.
     */
    var tnkMultiFileNames: Long = 0

    fun tnkProbe(node: Node, bucket: Int) {
        // Round 900's law: the guard cannot protect an ARGUMENT, so the caller
        // passes the NODE and every derivation happens below this line.
        if (!typeNodeKeyCensus) return
        tnkCalls++
        when (bucket) {
            TNK_HIT -> tnkHits++
            TNK_MISS -> tnkMisses++
            else -> tnkBypassed++
        }
        if ((node as NodeBase).nodeId < 0) tnkUnindexed++
        val n = subtreeSize(node)
        tnkSubtreeSum += n.toLong()
        if (n > tnkMaxSubtree) tnkMaxSubtree = n
        tnkProbeSizeHistogram[if (n >= 11) 11 else n]++
    }

    /**
     * The OBJECT-weighted arm, swept from the live cache's key set at the end of
     * the check. Iterating a `HashMap`'s keys hashes nothing, so this costs one
     * subtree walk per distinct key and no probe at all — which is the only way
     * the two weightings can be taken from the SAME rebuild (round 861: a
     * sub-population and the row it is read against may not come from two draws).
     */
    fun tnkSweepKeys(keys: Collection<Node>, parallelEntries: Int, multiFileNames: Int) {
        if (!typeNodeKeyCensus) return
        tnkParallelEntries += parallelEntries.toLong()
        tnkMultiFileNames += multiFileNames.toLong()
        for (k in keys) {
            tnkObjects++
            if ((k as NodeBase).nodeId < 0) tnkObjectUnindexed++
            val n = subtreeSize(k)
            tnkObjectSubtreeSum += n.toLong()
            tnkObjectSizeHistogram[if (n >= 11) 11 else n]++
        }
    }

    /** Nodes in [n]'s subtree, including [n] — the recursive `hashCode()` frame count. */
    private fun subtreeSize(n: Node): Int {
        var count = 1
        forEachChild(n) { count += subtreeSize(it) }
        return count
    }

    var tnkAmpCalls: Long = 0
    var tnkAmpMapNanos: Long = 0
    var tnkAmpLongNanos: Long = 0
    var tnkAmpRefNanos: Long = 0

    /**
     * Per-arm sinks, split for round 901's reason: one shared sink cannot tell a
     * DROPPED arm from a running one. Each must be an exact multiple of `r`
     * (round 759's arithmetic falsifier), and [tnkAmpLongSink] must EQUAL
     * [tnkAmpMapSink] — the parallel map is populated in lockstep with
     * `nodeTypes`, so a probe that finds one must find the other, and any
     * inequality means the successor key is not a bijection on this population.
     */
    var tnkAmpMapSink: Long = 0
    var tnkAmpLongSink: Long = 0
    var tnkAmpRefSink: Long = 0

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
        lexAmpMapSink = 0; lexAmpFilterSink = 0
        lexAmpScanNanos = 0; lexAmpScanSink = 0
        lexProbeSizeSum = 0; lexScanSteps = 0
        lexAmpHybridNanos = 0; lexAmpHybridSink = 0
        lexHybridScanned = 0; lexHybridFellBack = 0
        for (i in lexProbeSizeHistogram.indices) lexProbeSizeHistogram[i] = 0
        lexScopesBound = 0; lexScopeBoundKeys = 0
        for (i in lexBoundHistogram.indices) lexBoundHistogram[i] = 0
        perFileAmpNanos = 0; perFileAmpCalls = 0; sink = 0
        tnkCalls = 0; tnkHits = 0; tnkMisses = 0; tnkBypassed = 0
        tnkUnindexed = 0; tnkSubtreeSum = 0; tnkMaxSubtree = 0
        tnkObjects = 0; tnkObjectSubtreeSum = 0; tnkObjectUnindexed = 0
        tnkParallelEntries = 0; tnkStores = 0; tnkMultiFileNames = 0
        for (i in tnkProbeSizeHistogram.indices) tnkProbeSizeHistogram[i] = 0
        for (i in tnkObjectSizeHistogram.indices) tnkObjectSizeHistogram[i] = 0
        tnkAmpCalls = 0; tnkAmpMapNanos = 0; tnkAmpLongNanos = 0; tnkAmpRefNanos = 0
        tnkAmpMapSink = 0; tnkAmpLongSink = 0; tnkAmpRefSink = 0
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
        appendLine(
            "       bound scopes by own-symbol count 0..8 then 9+: " +
                lexBoundHistogram.joinToString(" ")
        )
        if (lexAmpCalls > 0) {
            appendLine(
                "       amplified r=$lexLevelAmp over $lexAmpCalls calls:  " +
                    "MAP p(r)=${lexAmpMapNanos / lexAmpCalls} ns   " +
                    "FILTER p(r)=${lexAmpFilterNanos / lexAmpCalls} ns   " +
                    "SCAN p(r)=${lexAmpScanNanos / lexAmpCalls} ns   " +
                    "HYBRID p(r)=${lexAmpHybridNanos / lexAmpCalls} ns   " +
                    "delta map-filter=${(lexAmpMapNanos - lexAmpFilterNanos) / lexAmpCalls} ns   " +
                    "delta map-scan=${(lexAmpMapNanos - lexAmpScanNanos) / lexAmpCalls} ns   " +
                    "delta map-hybrid=${(lexAmpMapNanos - lexAmpHybridNanos) / lexAmpCalls} ns   " +
                    "sink map=$lexAmpMapSink filter=$lexAmpFilterSink scan=$lexAmpScanSink " +
                    "hybrid=$lexAmpHybridSink (filter must be >=, scan and hybrid must be ==)   " +
                    "hybrid split: scanned=$lexHybridScanned fellBack=$lexHybridFellBack"
            )
            appendLine("       the boundary cancels BETWEEN the arms at equal r; two r give each slope")
            appendLine(
                "       (WARM.29) PROBE-weighted level size: mean " +
                    "${lexProbeSizeSum.toDouble() / maxOf(lexAmpCalls, 1)} own symbols, " +
                    "scan steps/probe ${lexScanSteps.toDouble() / maxOf(lexAmpCalls, 1)}   " +
                    "buckets 0..8 then 9+: ${lexProbeSizeHistogram.joinToString(" ")}"
            )
        }
        if (tnkCalls > 0 || tnkAmpCalls > 0) {
            appendLine(
                "  (WARM.30) nodeTypes deep AST-value key: calls=$tnkCalls " +
                    "hits=$tnkHits misses=$tnkMisses bypassed=$tnkBypassed " +
                    "UNINDEXED keys (nodeId<0)=$tnkUnindexed"
            )
            appendLine(
                "       deep hashes/rebuild = hits + 4*misses + bypassed's isPerFileDependentRefNode " +
                    "=> get $tnkHits+$tnkMisses, sentinel add/remove ${2 * tnkMisses}, put $tnkMisses " +
                    "= ${tnkHits + 4 * tnkMisses} hashes"
            )
            appendLine(
                "       PROBE-weighted key subtree: mean " +
                    "${tnkSubtreeSum.toDouble() / maxOf(tnkCalls, 1)} nodes, max $tnkMaxSubtree, " +
                    "buckets 0..10 then 11+: ${tnkProbeSizeHistogram.joinToString(" ")}"
            )
            appendLine(
                "       OBJECT-weighted (distinct cache keys): objects=$tnkObjects mean " +
                    "${tnkObjectSubtreeSum.toDouble() / maxOf(tnkObjects, 1)} nodes, " +
                    "unindexed=$tnkObjectUnindexed  parallel (file,nodeId) entries=$tnkParallelEntries " +
                    "stores=$tnkStores   buckets: ${tnkObjectSizeHistogram.joinToString(" ")}"
            )
            appendLine(
                "       REACHED control for amplifier arm C: multiFileModuleTypeNames=$tnkMultiFileNames " +
                    "(ZERO means isPerFileDependentRefNode returns at its first line and arm C prices " +
                    "an isEmpty() check, not a subtree walk — round 902)"
            )
        }
        if (tnkAmpCalls > 0) {
            appendLine(
                "       amplified r=$typeNodeKeyAmp over $tnkAmpCalls calls:  " +
                    "A map(deep key) p(r)=${tnkAmpMapNanos / tnkAmpCalls} ns   " +
                    "B LongKeyMap(file,nodeId) p(r)=${tnkAmpLongNanos / tnkAmpCalls} ns   " +
                    "C isPerFileDependentRefNode p(r)=${tnkAmpRefNanos / tnkAmpCalls} ns   " +
                    "A-B=${(tnkAmpMapNanos - tnkAmpLongNanos) / tnkAmpCalls} ns   " +
                    "sink A=$tnkAmpMapSink B=$tnkAmpLongSink C=$tnkAmpRefSink " +
                    "(every sink an exact multiple of r; (A - B) / r = " +
                    "${(tnkAmpMapSink - tnkAmpLongSink) / maxOf(typeNodeKeyAmp, 1)} probes the DEEP key " +
                    "served from a structurally-equal but DISTINCT node — the whole semantic content " +
                    "a (file, nodeId) re-key would lose)"
            )
            appendLine(
                "       sink mod r: A=${tnkAmpMapSink % maxOf(typeNodeKeyAmp, 1)} " +
                    "B=${tnkAmpLongSink % maxOf(typeNodeKeyAmp, 1)} " +
                    "C=${tnkAmpRefSink % maxOf(typeNodeKeyAmp, 1)}   " +
                    "— a FLAT p(r) between two r means C2 elided that arm, which no sink can see"
            )
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
