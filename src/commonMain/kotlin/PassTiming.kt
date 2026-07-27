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

import kotlin.time.TimeSource

/**
 * INV.0 — opt-in per-pass instrumentation of the checker's init dispatch
 * (see docs/ARCHITECTURE-RETHINK.md § 5 and the PLAN-PHASE-5.md QUEUE).
 *
 * The checker's `init` sequentially dispatches ~513 full-program passes; this
 * singleton records, when [enabled], the wall time of each named pass plus the
 * counters that quantify the architecture's recomputation multiplier:
 *
 *  - `getTypeOfExpression` invocations vs (approximately) distinct expression
 *    nodes — the expression-recompute factor;
 *  - `getTypeFromTypeNode` cacheable vs context-bypassed resolutions (and cache
 *    hits) — how often the one node-type cache is actually usable;
 *  - flow-narrowing walks launched (every depth-0 walk routes through
 *    `flowWalkWithTripCheck`), attributed to the launching pass — the
 *    per-consumer narrowing re-walk count.
 *
 * OFF BY DEFAULT and behavior-free: every hook is additive (`if
 * (PassTiming.enabled) …` around pure counter writes), so a disabled run is
 * byte-identical to an uninstrumented build — the full corpus suite and the
 * `--listAll` A/B pin that. Enable via the CLI flag `--passTiming` (Main.kt),
 * which resets, enables, compiles, and dumps the sorted table.
 *
 * Not thread-safe by design: the pipeline is single-threaded (the deep-stack
 * thread handoff in DeepStack.kt happens-before both directions), and the flag
 * stays `false` under the test suite, where hooks are dead branch-not-taken
 * field reads.
 */
object PassTiming {

    /** Master switch — off by default. Every hook below is inert when false. */
    var enabled: Boolean = false

    /** M0.1 tail-triage lab: LIGHT census mode — [pass] records ONLY the
     *  per-pass emitted-diagnostic deltas ([diagsByPass], via [diagnosticsSize])
     *  without the full instrumentation's counters/sets, so a whole corpus-suite
     *  run can accumulate the union cheaply. Off by default; behavior-free by
     *  construction (size reads only). Set by the JVM-only PassLab file hook or
     *  by tests. NOT thread-safe — never combine with `--workers`. */
    var censusMode: Boolean = false

    /** M0.1 tail-triage lab: init-dispatch pass names whose [pass] BODY is
     *  SKIPPED — the batch-disable instrument for the census protocol
     *  (PLAN-PHASE-5.md (M0.1)). Empty by default (one `isNotEmpty()` field
     *  read per pass call); a non-empty set deliberately CHANGES compiler
     *  behavior — any run using it is an experiment, never a gate. */
    var disabledPasses: Set<String> = emptySet()

    /** [censusMode]'s accumulator: per-pass emitted-diagnostic counts summed
     *  across EVERY compile in the process. Deliberately NOT cleared by
     *  [reset] — process-lifetime, so mid-suite tests that reset the regular
     *  counters cannot wipe the corpus census (the PassLab shutdown hook dumps
     *  it at JVM exit). */
    val censusByPass = LinkedHashMap<String, Int>()

    /** Name of the init-dispatch pass currently executing, for counter
     *  attribution; null outside any wrapped pass (setup region, lazy work
     *  triggered before/after the dispatch). Maintained by [pass]. */
    var currentPass: String? = null

    /** Accumulated wall time per pass name, insertion-ordered (= first-execution
     *  order, matching the init dispatch sequence). */
    val passNanos = LinkedHashMap<String, Long>()

    /** Invocation count per pass name (a name re-enters when a wrapped call sits
     *  inside a loop or runs for several option branches). */
    val passCalls = HashMap<String, Int>()

    var getTypeOfExpressionCalls: Long = 0

    /** Approximate distinct expression nodes touched by `getTypeOfExpression`,
     *  keyed `(pos shl 32) or end` — positions collide across files in a
     *  multi-file program (each file starts at 0), so this slightly UNDERCOUNTS
     *  distinct nodes and correspondingly OVERSTATES the recompute factor;
     *  labeled `~` in the dump. */
    val getTypeOfExpressionDistinct = HashSet<Long>()

    /** `getTypeOfExpression` invocations attributed to the launching pass. */
    val getTypeOfExpressionByPass = HashMap<String, Long>()

    /** `getTypeFromTypeNode` resolutions that could consult the `nodeTypes`
     *  cache (no resolution context active). */
    var typeNodeCacheable: Long = 0

    /** Of [typeNodeCacheable], how many were served from the cache. */
    var typeNodeCacheHits: Long = 0

    /** `getTypeFromTypeNode` resolutions forced to recompute because a
     *  resolution context (type-param scope / inference namespace / alias args /
     *  conflated-interface node) was active. */
    var typeNodeBypassed: Long = 0

    // --- INV.5(c) context-keyed cache (`mappedNodeTypes`) attribution --------
    // The bypassed population above is the port's whole prize: each of those
    // resolutions is one tsc would have served from NodeLinks. These split it by
    // WHAT actually happens, so the campaign can be sized instead of guessed.

    /** Of [typeNodeBypassed], served from the context-keyed cache. */
    var mappedHits: Long = 0

    /** INV.5(c2) diagnosis: recompute every served hit and compare, so a
     *  missing key dimension is NAMED rather than guessed. Costly — opt in. */
    var verifyMappedCache: Boolean = false

    /** Of [mappedHits], served a type whose SHAPE differs from a fresh
     *  recompute — a genuinely missing key dimension. */
    var mappedServeWrong: Long = 0

    /** Of [mappedHits], served a structurally-IDENTICAL type that merely has a
     *  different `Type.id` — the non-canonical-identity disease, not a keying
     *  bug. Fresh minting is what makes these differ at all. */
    var mappedServeIdOnly: Long = 0

    /** INV.5(c4): times the context fingerprint was actually BUILT (vs served
     *  from the one-entry memo) — the cost an interned TypeMapper removes. */
    var ctxFingerprintBuilds: Long = 0

    /** INV.5(c5): wall nanos spent inside OUTERMOST context-bypassed
     *  `getTypeFromTypeNodeWorker` calls — the total prize any context-keyed
     *  cache competes for. Nested calls are excluded by a depth guard, so this
     *  is exactly the time a perfect zero-cost cache could remove. */
    var bypassedResolveNanos: Long = 0
    var bypassedResolveOutermost: Long = 0

    /** INV.4(g) round 706: attribution INSIDE checkSpine — the walk dominates
     *  init (83%) while the whole type system accounts for only ~30% of it, so
     *  these split the remainder into its actual phases. */
    var spineNodes: Long = 0
    var spineEnterNanos: Long = 0
    var spineLeaveNanos: Long = 0
    var spineScopeNanos: Long = 0
    var spineUResNanos: Long = 0
    var spineChildrenNanos: Long = 0

    /** INV.4(g): per-NodeKind enter+leave nanos and counts. If a kind almost no
     *  handler cares about still costs microseconds, the spine's cost is
     *  CONSULTATION (every handler asked about every node), not work — which is
     *  the difference between a dispatch-table fix and a handler-cost fix. */
    val spineKindNanos = HashMap<Int, Long>()
    val spineKindCount = HashMap<Int, Long>()

    /** Of [typeNodeBypassed], keyed and computed (a cold miss — unavoidable). */
    var mappedMisses: Long = 0

    /** Of [typeNodeBypassed], NOT cacheable: the node carries no nodeId. */
    var mappedRejectUnindexed: Long = 0

    /** Of [typeNodeBypassed], NOT cacheable: the node's owning file could not
     *  be determined. */
    var mappedRejectNoOwner: Long = 0

    /** Of [typeNodeBypassed], NOT cacheable ONLY because the round-548
     *  conservative gate demands `currentFileLocals` be the node's OWN file's
     *  locals — i.e. every cross-file type reference. This is the number the
     *  gate-relaxation experiment targets. */
    var mappedRejectForeignFile: Long = 0

    /** Depth-0 flow-narrowing walks launched (`flowWalkWithTripCheck` entries). */
    var narrowWalks: Long = 0

    /** Narrowing walks attributed to the launching pass. */
    val narrowWalksByPass = HashMap<String, Long>()

    /** M0 census: a size view of the current checker's diagnostics list,
     *  registered at init start (enabled-only) so [pass] can attribute emitted
     *  diagnostics to the emitting pass. */
    var diagnosticsSize: (() -> Int)? = null

    /** M0 census: diagnostics emitted per pass — positive size deltas only
     *  (emit-twice truncations inside a pass clamp to 0; a nested wrapped pass
     *  double-attributes into its enclosing pass — both safe in the KEEP
     *  direction for the tail triage). */
    val diagsByPass = LinkedHashMap<String, Int>()

    /** M0 census: node instances per node-class simple name, counted once per
     *  indexed node by `indexSourceFile` — the dispatch-order / kind-table
     *  design input.
     *
     *  **APPROXIMATE, AND ALWAYS LOW (measured round 717).** `indexSourceFile`
     *  runs on the crawl's CONCURRENT parse threads (ProjectCompiler
     *  `readAndScanBatch` — `Dispatchers.Default`, FRONTEND_CONCURRENCY in
     *  flight) and this is a plain HashMap, so increments are lost to a data
     *  race: two runs of the same binary measured 857,350 and 854,550 (−0.33%)
     *  while every single-threaded counter was bit-identical. Fine for "which
     *  kinds dominate" (the error is a fraction of a percent, spread over the
     *  hot kinds); NOT fine as a gate value or as an exact per-kind population —
     *  see (DISPATCH.1), whose handler table must be derived from an exact
     *  census. Excluded from the COST.1 gate for this reason. */
    val nodeKindHistogram = HashMap<String, Long>()

    fun noteNodeKind(node: Any) {
        val k = node::class.simpleName ?: "?"
        nodeKindHistogram[k] = (nodeKindHistogram[k] ?: 0L) + 1
    }

    /** INV.1(e): files whose crawl-time pre-parse the multi-file core REUSED
     *  (content + flags matched) vs parsed FRESH (no pre-parse, or mismatch). */
    var preParseReused: Long = 0
    var preParseFresh: Long = 0

    /** INV.3(a): keyed `globals` lookups (get/containsKey), classified by the
     *  checker-installed classifier (see `Checker.installGlobalsLookupClassifier`)
     *  against the per-file visibility model INV.3 migrates to. One counter per
     *  [GlobalsLookupClass]; the invariant `globalsLookups == sum(classes)` is
     *  pinned by Inv3GlobalsLookupTest. */
    var globalsLookups: Long = 0
    var globalsMisses: Long = 0
    var globalsTrueGlobalHits: Long = 0
    var globalsSharedHits: Long = 0
    var globalsOwnLocalHits: Long = 0
    var globalsConflatedHits: Long = 0
    var globalsUnscopedHits: Long = 0

    /** Names / passes behind [globalsConflatedHits] — the INV.3 migration worklist. */
    val globalsConflatedByName = HashMap<String, Long>()
    val globalsConflatedByPass = HashMap<String, Long>()

    /** Names / passes behind [globalsUnscopedHits] (no file context at the site —
     *  the lookup MAY be the current file's own local; also an INV.4 datum: how
     *  much checking runs without file attribution). */
    val globalsUnscopedByName = HashMap<String, Long>()
    val globalsUnscopedByPass = HashMap<String, Long>()

    /** Total wall time between checker-init entry and dispatch end, summed over
     *  every checker constructed in the run (a compile may build more than one). */
    var checkerInitNanos: Long = 0

    private var initMark: TimeSource.Monotonic.ValueTimeMark? = null

    private const val OUTSIDE_PASS = "(outside init dispatch)"

    fun reset() {
        currentPass = null
        passNanos.clear()
        passCalls.clear()
        getTypeOfExpressionCalls = 0
        typeOfExprLastResult.clear()
        shadowMemoHitCorrect = 0; shadowMemoHitWrong = 0; shadowMemoMiss = 0
        narrowWalkNanos = 0; typeOfExprNanos = 0
        relationNanos = 0; typeNodeNanos = 0; memberResolveNanos = 0
        walkRepeatIdentical = 0; walkRepeatStructuralUnion = 0; walkRepeatDiff = 0; walkMiss = 0
        walkRepeatNanos = 0
        walkMemoServed = 0; exprSavableNanos = 0; exprSavableCalls = 0; walkMissCold = 0; walkMissEpochIdentical = 0; walkMissEpochStructural = 0
        walkMissEpochDiff = 0; walkMissEpochDeltaSum = 0
        epochBumps.clear(); epochBlame.clear(); epochNoops.clear()
        depServeIdentical = 0; depServeStructural = 0; depServeWrong = 0
        depCold = 0; depNoPath = 0; depInvalidated = 0; depInvalidatedBy.clear()
        depWrongBy.clear(); depWrongSamples.clear()
        exprResultKind.clear()
        unstableRepeatStructural = 0; unstableRepeatDiff = 0; unstableRepeatCold = 0
        unstableStructuralBy.clear()
        typeOfExprRepeatSame = 0
        typeOfExprRepeatDiff = 0
        resetCallerAttribution()
        getTypeOfExpressionDistinct.clear()
        getTypeOfExpressionByPass.clear()
        typeNodeCacheable = 0
        typeNodeCacheHits = 0
        typeNodeBypassed = 0
        mappedHits = 0
        mappedServeWrong = 0
        mappedServeIdOnly = 0
        ctxFingerprintBuilds = 0
        bypassedResolveNanos = 0
        bypassedResolveOutermost = 0
        spineNodes = 0; spineEnterNanos = 0; spineLeaveNanos = 0
        spineScopeNanos = 0; spineUResNanos = 0; spineChildrenNanos = 0
        spineKindNanos.clear(); spineKindCount.clear()
        mappedMisses = 0
        mappedRejectUnindexed = 0
        mappedRejectNoOwner = 0
        mappedRejectForeignFile = 0
        narrowWalks = 0
        narrowWalksByPass.clear()
        narrowWalkBucketCalls = LongArray(4)
        narrowWalkBucketNanos = LongArray(4)
        narrowWalkTripped = 0
        narrowWalkTrippedNanos = 0
        narrowWalkHugeByKind.clear()
        narrowWalkHugeVisits = 0
        narrowWalkHugeVisitsMax = 0
        narrowWalkAllVisits = 0
        diagnosticsSize = null
        diagsByPass.clear()
        nodeKindHistogram.clear()
        preParseReused = 0
        preParseFresh = 0
        globalsLookups = 0
        globalsMisses = 0
        globalsTrueGlobalHits = 0
        globalsSharedHits = 0
        globalsOwnLocalHits = 0
        globalsConflatedHits = 0
        globalsUnscopedHits = 0
        globalsConflatedByName.clear()
        globalsConflatedByPass.clear()
        globalsUnscopedByName.clear()
        globalsUnscopedByPass.clear()
        checkerInitNanos = 0
        initMark = null
    }

    fun notePass(name: String, nanos: Long) {
        passNanos[name] = (passNanos[name] ?: 0L) + nanos
        passCalls[name] = (passCalls[name] ?: 0) + 1
    }

    // (f1) round 594 probe: per-node repeat-result classification — how much
    // of the recompute returns the IDENTICAL Type instance (memoizable) vs a
    // different one (needs invalidation). pos-keyed (the documented cross-file
    // collision adds minor noise, same as the distinct counter).
    // (f1b-i) round 595: the SHADOW-memo verdicts — hitWrong > 0 means the
    // epoch invalidation surface has a gap (an untracked mutation site).
    // (f2) round 597 probe: TIME attribution inside checkSpine.
    var narrowWalkNanos: Long = 0
    var typeOfExprNanos: Long = 0
    // (f2) round 599: walk-result repeat classification.
    var walkRepeatIdentical: Long = 0
    var walkRepeatStructuralUnion: Long = 0
    var walkRepeatDiff: Long = 0
    var walkMiss: Long = 0
    var walkRepeatNanos: Long = 0

    // (M1)(a) round 660: the epoch-churn ATTRIBUTION split. `walkMiss` above
    // conflated two very different things — a reference seen for the FIRST time
    // (cold, unavoidable) and a reference seen before whose memo entry was
    // INVALIDATED by an epoch bump. Only the second is churn, and the decisive
    // sub-question is whether the invalidating state change mattered AT ALL for
    // that reference: an epoch-invalidated repeat whose recomputed result is
    // IDENTICAL is a fence that is too coarse, i.e. directly recoverable by
    // splitting read-relevant from record-only state (or fencing per map).
    /** (M1)(d) round 665: what a LIVE expression memo would actually save —
     *  the core-compute time of calls the shadow would have SERVED, counted
     *  only at the OUTERMOST such call so nested servable calls are not
     *  double-counted (serving an outer call skips its whole subtree). This is
     *  the measure-before-building number round 664 insisted on: the walk memo
     *  netted 60% of its shadow estimate once per-call overhead was paid, and
     *  the expression path has ~6x the calls at a fraction of the cost each. */
    var exprSavableNanos: Long = 0
    var exprSavableCalls: Long = 0

    /**
     * (CALL.2) round 735: the flow-narrowing walk COST DISTRIBUTION,
     * `[<10 us, <100 us, <1 ms, >=1 ms]`, plus the trip correlation. Round 735
     * measured the 9,615 walks launched from `checkArgumentsAgainstSignature`
     * and found 0.9% of them (87 walks at >=1 ms) carrying 55% of the cost —
     * a mean of 62 us describes NEITHER population. Whether that shape holds
     * compile-wide, and whether the monsters are the TRIPPED walks (which are
     * deliberately never memoized, so they re-run in full at every visit),
     * decides what a stage-4 fix would even be.
     */
    var narrowWalkBucketCalls: LongArray = LongArray(4)
    var narrowWalkBucketNanos: LongArray = LongArray(4)
    var narrowWalkTripped: Long = 0
    var narrowWalkTrippedNanos: Long = 0
    /** Walks at >= 1 ms, by walk kind — is the tail one call site or all of them? */
    val narrowWalkHugeByKind = HashMap<String, Long>()

    /**
     * Flow-node VISITS consumed by the >= 1 ms walks. A walk is bounded by
     * `NARROW_VISIT_BUDGET` = 1,000,000 node arrivals, and the decisive
     * question about the tail is whether a 4 ms walk is a walk that arrives at
     * ~1 M nodes (an intra-walk revisit blow-up the per-node memo is failing to
     * damp) or a small walk whose per-node work is expensive.
     */
    var narrowWalkHugeVisits: Long = 0
    var narrowWalkHugeVisitsMax: Long = 0
    var narrowWalkAllVisits: Long = 0

    fun noteNarrowWalkVisits(visits: Long, huge: Boolean) {
        narrowWalkAllVisits += visits
        if (huge) {
            narrowWalkHugeVisits += visits
            if (visits > narrowWalkHugeVisitsMax) narrowWalkHugeVisitsMax = visits
        }
    }

    fun noteNarrowWalkCost(nanos: Long, tripped: Boolean, kind: Int) {
        val b = if (nanos < 10_000L) 0 else if (nanos < 100_000L) 1
        else if (nanos < 1_000_000L) 2 else 3
        narrowWalkBucketCalls[b]++
        narrowWalkBucketNanos[b] += nanos
        if (tripped) { narrowWalkTripped++; narrowWalkTrippedNanos += nanos }
        if (b == 3) {
            val k = "k$kind${if (tripped) "-TRIP" else ""}"
            narrowWalkHugeByKind[k] = (narrowWalkHugeByKind[k] ?: 0L) + 1
        }
    }

    /** (M1)(c) round 664: LIVE memo serves (walks skipped entirely). */
    var walkMemoServed: Long = 0
    var walkMissCold: Long = 0
    var walkMissEpochIdentical: Long = 0
    var walkMissEpochStructural: Long = 0
    var walkMissEpochDiff: Long = 0
    /** Sum of (epoch now − epoch at the previous walk of the same reference) —
     *  a small mean means single bumps are doing the invalidating. */
    var walkMissEpochDeltaSum: Long = 0
    /** Epoch bumps by SOURCE field/collection (every fenced setter is tagged). */
    val epochBumps = HashMap<String, Long>()
    /** For each epoch-invalidated repeat, the source of the LAST bump before it
     *  — an approximation of "who invalidated this walk", exact when the delta
     *  is 1. */
    val epochBlame = HashMap<String, Long>()

    fun noteEpochBump(src: String) {
        epochBumps[src] = (epochBumps[src] ?: 0L) + 1L
        lastEpochBumpSource = src
    }

    /** The source tag of the most recent epoch bump (not reset per walk). */
    var lastEpochBumpSource: String = "(none)"

    fun noteEpochBlame(src: String) {
        epochBlame[src] = (epochBlame[src] ?: 0L) + 1L
    }

    /** (M1)(a) round 660: assignments to a fenced field that did NOT change it
     *  (`field === v`) — save/restore round-trips and re-installs of the same
     *  instance. These used to bump the fence and invalidate every memo entry
     *  for nothing; the setters now skip the bump and count here instead. */
    val epochNoops = HashMap<String, Long>()

    fun noteEpochNoop(src: String) {
        epochNoops[src] = (epochNoops[src] ?: 0L) + 1L
    }

    // (M1)(b) round 661: the DEPENDENCY-KEYED validity shadow. Round 660 showed
    // the global fence invalidates ~34.5 k walk repeats that would recompute
    // IDENTICALLY, and that a finer fence over the same key space cannot help
    // (96% of blame is genuine currentLocalTypes/currentFlowGraph SWAPS). So
    // this shadow fences on what a walk of reference R actually READS instead:
    // the identity of the FlowGraph plus the Type currently bound to R's ROOT
    // NAME in the localTypes family. A swap to a different map that still binds
    // the root to the same Type instance is then NOT an invalidation — which is
    // exactly the population the global fence throws away.
    // Probe-only; `depServeWrong` MUST be 0 before any live memo is considered.
    var depServeIdentical: Long = 0
    var depServeStructural: Long = 0
    var depServeWrong: Long = 0
    var depCold: Long = 0
    var depNoPath: Long = 0
    var depInvalidated: Long = 0
    /** Which dependency differed on an invalidated repeat (graph / localType /
     *  narrowed / several) — says whether the remaining churn is the flow graph
     *  or the scope binding. */
    val depInvalidatedBy = HashMap<String, Long>()

    fun noteDepInvalidated(what: String) {
        depInvalidated++
        depInvalidatedBy[what] = (depInvalidatedBy[what] ?: 0L) + 1L
    }

    // (M1)(b1) round 662: characterise the WRONG serves before engineering a
    // read-set recorder. If they cluster on one mechanism the key can be
    // completed (or that shape excluded from serving) cheaply; if they are
    // diffuse across mechanisms, the walk's dependencies are not
    // name-enumerable and that is (b1)'s falsifiable answer.
    val depWrongBy = HashMap<String, Long>()
    val depWrongSamples = ArrayList<String>()

    // (M1)(b2) round 663: measure the CANONICAL-OUTPUT prize directly. The
    // expression shadow only admits instance-stable result kinds
    // (Intrinsic/Interface/Reference) because unions and literal types are
    // freshly minted per call — that IS the non-canonical-output problem, and
    // it silently excludes those calls from the memo's denominator. These
    // counters put them back: how often each kind is returned, and for the
    // NON-stable ones whether a same-epoch repeat is STRUCTURALLY equal (i.e.
    // interning the output would make it servable) or genuinely different.
    val exprResultKind = HashMap<String, Long>()
    var unstableRepeatStructural: Long = 0
    var unstableRepeatDiff: Long = 0
    var unstableRepeatCold: Long = 0

    /** (M1)(b2): the structural-repeat population SPLIT BY KIND — decisive,
     *  because unions are safely internable while object-literal freshness is
     *  semantically load-bearing (the freshObjLitRange relation machinery), so
     *  only part of this population is actually reachable. */
    val unstableStructuralBy = HashMap<String, Long>()

    fun noteUnstableStructural(kind: String) {
        unstableRepeatStructural++
        unstableStructuralBy[kind] = (unstableStructuralBy[kind] ?: 0L) + 1L
    }

    fun noteExprResultKind(kind: String) {
        exprResultKind[kind] = (exprResultKind[kind] ?: 0L) + 1L
    }

    fun noteDepWrong(shape: String, sample: String) {
        depServeWrong++
        depWrongBy[shape] = (depWrongBy[shape] ?: 0L) + 1L
        if (depWrongSamples.size < 20) depWrongSamples.add(sample)
    }

    var relationNanos: Long = 0
    var typeNodeNanos: Long = 0
    var memberResolveNanos: Long = 0
    private val probeClock = TimeSource.Monotonic.markNow()
    fun nowNanos(): Long = probeClock.elapsedNow().inWholeNanoseconds

    var shadowMemoHitCorrect: Long = 0
    var shadowMemoHitWrong: Long = 0
    var shadowMemoMiss: Long = 0

    val typeOfExprLastResult = HashMap<Long, Any>()
    var typeOfExprRepeatSame: Long = 0
    var typeOfExprRepeatDiff: Long = 0

    fun noteTypeOfExprResult(pos: Int, end: Int, r: Any) {
        val k = (pos.toLong() shl 32) or (end.toLong() and 0xFFFF_FFFFL)
        val prev = typeOfExprLastResult.put(k, r)
        if (prev != null) { if (prev === r) typeOfExprRepeatSame++ else typeOfExprRepeatDiff++ }
    }

    fun noteGetTypeOfExpression(pos: Int, end: Int) {
        getTypeOfExpressionCalls++
        getTypeOfExpressionDistinct.add((pos.toLong() shl 32) or (end.toLong() and 0xFFFF_FFFFL))
        val p = currentPass ?: OUTSIDE_PASS
        getTypeOfExpressionByPass[p] = (getTypeOfExpressionByPass[p] ?: 0L) + 1
    }

    fun noteNarrowWalk() {
        narrowWalks++
        val p = currentPass ?: OUTSIDE_PASS
        narrowWalksByPass[p] = (narrowWalksByPass[p] ?: 0L) + 1
    }

    // ---------------------------------------------------------------------
    // (TYPE.1) round 737 — attribute the getTypeOfExpression calls BY CALLER.
    //
    // ARCHITECTURE-RETHINK § 0.1 stage 3 sizes "cut the x2.7 recompute factor"
    // at up to ~9% and names its mechanism as "several handlers independently
    // type the same node". That is a CO-OCCURRENCE claim, and no round had
    // tested it: the aggregate factor cannot distinguish it from ordinary
    // recursion (typing `a.b.c` makes three calls at three distinct nodes and
    // recomputes nothing).
    //
    // The measurement therefore records, per call, the ORIGIN — the caller of
    // the OUTERMOST `getTypeOfExpression` on the stack. Nested calls inherit
    // it, so a whole expression subtree is attributed to the handler that
    // asked for it, and the recursion never inflates a caller's factor.
    //
    // Enabled by `--typeOfExprCallers` only; `callerAttr` is a MODE, so
    // [reset] must not clear it (same contract as `verifyMappedCache`).
    // ---------------------------------------------------------------------

    /** Attribution mode. Never true in a production compile. */
    var callerAttr = false

    /** Site ids are dense; 256 fit the four-word per-node mask below. */
    const val MAX_CALLER_SITES = 256

    private val callerSiteIds = HashMap<String, Int>()
    val callerSiteNames = ArrayList<String>()
    val callerSiteCalls = LongArray(MAX_CALLER_SITES)
    val callerSiteOutermost = LongArray(MAX_CALLER_SITES)
    val callerSiteNanos = LongArray(MAX_CALLER_SITES)

    /** Methods whose frames are the probe itself, never an attributable caller. */
    val callerSkipMethods = setOf(
        "captureCallerFrames", "getTypeOfExpression", "internCallerSite"
    )

    /**
     * Per expression node: which origins typed it, how often, and whether a
     * repeat was a fresh OUTERMOST typing (the only kind single-visit
     * discipline could remove).
     */
    class ExprNodeAttr {
        var m0: Long = 0; var m1: Long = 0; var m2: Long = 0; var m3: Long = 0
        var calls: Int = 0
        var outermost: Int = 0
        var firstOrigin: Int = -1
    }

    val exprNodeAttrs = HashMap<Long, ExprNodeAttr>()

    /** Node keys re-salted by the file being checked — the collision census. */
    val saltedNodeKeys = HashSet<Long>()

    /**
     * Time spent in OUTERMOST typings of a node that had ALREADY been
     * outermost-typed — the upper bound on stage 3's prize, since typing every
     * node at most once at top level is the most single-visit discipline can
     * buy. Nested repeats are excluded on purpose: they are already inside
     * some outermost typing's time.
     */
    var redundantOutermostNanos: Long = 0
    var redundantOutermostCalls: Long = 0

    /** `(firstOrigin, repeatOrigin)` -> nanos / count of those redundant typings. */
    val redundantPairNanos = HashMap<Long, Long>()
    val redundantPairCalls = HashMap<Long, Long>()

    fun resetCallerAttribution() {
        callerSiteIds.clear(); callerSiteNames.clear()
        callerSiteCalls.fill(0); callerSiteOutermost.fill(0); callerSiteNanos.fill(0)
        exprNodeAttrs.clear(); saltedNodeKeys.clear()
        perfectMemoNanos = 0; perfectMemoCalls = 0
        redundantOutermostNanos = 0; redundantOutermostCalls = 0
        redundantPairNanos.clear(); redundantPairCalls.clear()
    }

    fun internCallerSite(sig: String): Int {
        val key = sig.ifEmpty { "(unattributed)" }
        callerSiteIds[key]?.let { return it }
        if (callerSiteNames.size >= MAX_CALLER_SITES - 1) {
            // Site 255 is the overflow bucket; it is never interned by name, so
            // the table stays dense and the mask stays four words.
            return MAX_CALLER_SITES - 1
        }
        val id = callerSiteNames.size
        callerSiteNames.add(key)
        callerSiteIds[key] = id
        return id
    }

    /**
     * The ABSOLUTE ceiling for stage 3 in any shape: the inclusive time of
     * every call a PERFECT per-node type cache (`NodeLinks.resolvedType`,
     * ignoring soundness and every ambient context) would have skipped, each
     * skipped subtree counted once. Nothing that removes recomputation — a
     * memo, single-visit discipline, or a handler merge — can beat it.
     */
    var perfectMemoNanos: Long = 0
    var perfectMemoCalls: Long = 0

    /** Records one call; returns how many calls this node has now had.
     *  [outermost] means `depth == 0` on entry. */
    fun noteTypeOfExprCaller(site: Int, nodeKey: Long, outermost: Boolean): Int {
        callerSiteCalls[site]++
        if (outermost) callerSiteOutermost[site]++
        val a = exprNodeAttrs.getOrPut(nodeKey) { ExprNodeAttr() }
        a.calls++
        when (site ushr 6) {
            0 -> a.m0 = a.m0 or (1L shl (site and 63))
            1 -> a.m1 = a.m1 or (1L shl (site and 63))
            2 -> a.m2 = a.m2 or (1L shl (site and 63))
            else -> a.m3 = a.m3 or (1L shl (site and 63))
        }
        return a.calls
    }

    /** Records the INCLUSIVE time of one completed outermost typing. */
    fun noteTypeOfExprOutermostDone(site: Int, nodeKey: Long, nanos: Long) {
        callerSiteNanos[site] += nanos
        val a = exprNodeAttrs[nodeKey] ?: return
        val repeat = a.outermost > 0
        a.outermost++
        if (a.firstOrigin < 0) a.firstOrigin = site
        if (repeat) {
            redundantOutermostNanos += nanos
            redundantOutermostCalls++
            val k = (a.firstOrigin.toLong() shl 32) or site.toLong()
            redundantPairNanos[k] = (redundantPairNanos[k] ?: 0L) + nanos
            redundantPairCalls[k] = (redundantPairCalls[k] ?: 0L) + 1L
        }
    }

    private fun siteName(id: Int): String =
        if (id in callerSiteNames.indices) callerSiteNames[id] else "(overflow)"

    /** The (TYPE.1) report; emitted only when the attribution ran. */
    fun dumpCallerAttribution(appendLine: (String) -> Unit) {
        if (exprNodeAttrs.isEmpty()) return
        appendLine("== (TYPE.1) getTypeOfExpression BY CALLER ==")
        val totalCalls = callerSiteCalls.sum()
        val totalOuter = callerSiteOutermost.sum()
        val totalNanos = callerSiteNanos.sum()
        appendLine(
            "sites=${callerSiteNames.size} calls=$totalCalls outermost=$totalOuter " +
                "distinctNodes=${exprNodeAttrs.size} (fileSalted ${saltedNodeKeys.size}) " +
                "inclusiveMs=${totalNanos / 1_000_000}"
        )
        // Per-site distinct-node counts, from the masks.
        val distinctBySite = LongArray(MAX_CALLER_SITES)
        val originsPerNode = HashMap<Int, Long>()
        var multiOriginNodes = 0L
        var multiOriginCalls = 0L
        for (a in exprNodeAttrs.values) {
            var n = 0
            var w = a.m0; var base = 0
            while (base < 256) {
                var bits = w
                while (bits != 0L) {
                    val b = bits.countTrailingZeroBits()
                    distinctBySite[base + b]++
                    n++
                    bits = bits and (bits - 1)
                }
                base += 64
                w = when (base) { 64 -> a.m1; 128 -> a.m2; 192 -> a.m3; else -> 0L }
            }
            originsPerNode[n] = (originsPerNode[n] ?: 0L) + 1L
            if (n > 1) { multiOriginNodes++; multiOriginCalls += a.calls }
        }
        appendLine(
            "${"calls".padStart(9)} ${"outer".padStart(8)} ${"distinct".padStart(9)} " +
                "${"factor".padStart(7)} ${"incl.ms".padStart(8)}  origin"
        )
        val order = (0 until MAX_CALLER_SITES).sortedByDescending { callerSiteCalls[it] }
        for (s in order) {
            if (callerSiteCalls[s] == 0L) continue
            val d = distinctBySite[s]
            val f = if (d > 0) callerSiteCalls[s] * 100 / d else 0L
            appendLine(
                "${callerSiteCalls[s].toString().padStart(9)} " +
                    "${callerSiteOutermost[s].toString().padStart(8)} " +
                    "${d.toString().padStart(9)} " +
                    "${"${f / 100}.${(f % 100).toString().padStart(2, '0')}".padStart(7)} " +
                    "${(callerSiteNanos[s] / 1_000_000).toString().padStart(8)}  ${siteName(s)}"
            )
        }
        appendLine("-- origins per node (the co-occurrence the stage-3 claim needs) --")
        for ((n, c) in originsPerNode.entries.sortedBy { it.key }) {
            appendLine("  ${n.toString().padStart(3)} origins: ${c.toString().padStart(9)} nodes")
        }
        appendLine(
            "  nodes with >1 origin: $multiOriginNodes of ${exprNodeAttrs.size} " +
                "(${multiOriginCalls} of $totalCalls calls land on them)"
        )
        appendLine(
            "-- REDUNDANT OUTERMOST typings (what single-visit discipline removes) --\n" +
                "  ${redundantOutermostCalls} repeat typings, " +
                "${redundantOutermostNanos / 1_000_000} ms inclusive"
        )
        appendLine(
            "-- PERFECT per-node cache (the ceiling for stage 3 in ANY shape) --\n" +
                "  ${perfectMemoCalls} served subtree roots, " +
                "${perfectMemoNanos / 1_000_000} ms inclusive"
        )
        appendLine("${"ms".padStart(8)} ${"calls".padStart(9)}  firstOrigin -> repeatOrigin")
        for ((k, v) in redundantPairNanos.entries.sortedByDescending { it.value }.take(40)) {
            val a = (k ushr 32).toInt()
            val b = (k and 0xFFFF_FFFFL).toInt()
            appendLine(
                "${(v / 1_000_000).toString().padStart(8)} " +
                    "${(redundantPairCalls[k] ?: 0L).toString().padStart(9)}  " +
                    "${siteName(a)} -> ${siteName(b)}"
            )
        }
    }

    internal fun noteGlobalsLookup(cls: GlobalsLookupClass, name: String) {
        globalsLookups++
        when (cls) {
            GlobalsLookupClass.MISS -> globalsMisses++
            GlobalsLookupClass.TRUE_GLOBAL -> globalsTrueGlobalHits++
            GlobalsLookupClass.SHARED -> globalsSharedHits++
            GlobalsLookupClass.OWN_LOCAL -> globalsOwnLocalHits++
            GlobalsLookupClass.CONFLATED -> {
                globalsConflatedHits++
                globalsConflatedByName[name] = (globalsConflatedByName[name] ?: 0L) + 1
                val p = currentPass ?: OUTSIDE_PASS
                globalsConflatedByPass[p] = (globalsConflatedByPass[p] ?: 0L) + 1
            }
            GlobalsLookupClass.UNSCOPED -> {
                globalsUnscopedHits++
                globalsUnscopedByName[name] = (globalsUnscopedByName[name] ?: 0L) + 1
                val p = currentPass ?: OUTSIDE_PASS
                globalsUnscopedByPass[p] = (globalsUnscopedByPass[p] ?: 0L) + 1
            }
        }
    }

    fun noteInitStart() {
        if (!enabled) return
        initMark = TimeSource.Monotonic.markNow()
    }

    fun noteInitEnd() {
        if (!enabled) return
        initMark?.let { checkerInitNanos += it.elapsedNow().inWholeNanoseconds }
        initMark = null
    }

    /** (M1)(a): mean epoch advance between two walks of the SAME reference —
     *  1.0 would mean a single bump invalidates each repeat. */
    private fun epochDeltaMean(): String {
        val n = walkMissEpochIdentical + walkMissEpochStructural + walkMissEpochDiff
        return if (n == 0L) "n/a" else ((walkMissEpochDeltaSum * 10 / n) / 10.0).toString()
    }

    /** Top-[k] "name=count" pairs, count-descending, for the churn tables. */
    private fun topCounts(m: Map<String, Long>, k: Int): String =
        if (m.isEmpty()) "(none)"
        else m.entries.sortedByDescending { it.value }.take(k)
            .joinToString(" ") { "${it.key}=${it.value}" }

    /** Render the sorted pass-time table + counters through [appendLine]. */
    fun dump(appendLine: (String) -> Unit) {
        appendLine("== xtsc pass timing (INV.0) ==")
        val sumPassNanos = passNanos.values.sum()
        appendLine(
            "checker-init total: ${ms(checkerInitNanos)} ms; " +
                "${passNanos.size} passes recorded, sum ${ms(sumPassNanos)} ms, " +
                "outside-pass ${ms(checkerInitNanos - sumPassNanos)} ms"
        )
        appendLine(
            "${"ms".padStart(10)} ${"calls".padStart(6)} " +
                "${"typeOfExpr".padStart(12)} ${"narrowWalks".padStart(12)}  pass"
        )
        for ((name, nanos) in passNanos.entries.sortedByDescending { it.value }) {
            appendLine(
                "${ms(nanos).padStart(10)} ${(passCalls[name] ?: 0).toString().padStart(6)} " +
                    "${(getTypeOfExpressionByPass[name] ?: 0L).toString().padStart(12)} " +
                    "${(narrowWalksByPass[name] ?: 0L).toString().padStart(12)}  $name"
            )
        }
        if (diagsByPass.isNotEmpty()) {
            appendLine("== emissions by pass (M0 census; positive deltas, nested double-attributed) ==")
            for ((name, n) in diagsByPass.entries.sortedByDescending { it.value }) {
                appendLine("  ${n.toString().padStart(7)}  $name")
            }
        }
        if (nodeKindHistogram.isNotEmpty()) {
            appendLine("== node kinds (indexSourceFile census) ==")
            val totalNodes = nodeKindHistogram.values.sum()
            var cum = 0L
            for ((k, v) in nodeKindHistogram.entries.sortedByDescending { it.value }) {
                cum += v
                val perMille = v * 1000 / totalNodes
                appendLine(
                    "  ${v.toString().padStart(9)} " +
                        "${"${perMille / 10}.${perMille % 10}%".padStart(6)} " +
                        "${(cum * 100 / totalNodes).toString().padStart(3)}%  $k"
                )
            }
            appendLine("  total $totalNodes nodes")
        }
        appendLine("== counters ==")
        val distinct = getTypeOfExpressionDistinct.size.toLong()
        val factor = if (distinct > 0) (getTypeOfExpressionCalls * 10 / distinct) else 0L
        appendLine(
            "walkRepeats: identical=$walkRepeatIdentical structuralUnion=$walkRepeatStructuralUnion diff=$walkRepeatDiff miss=$walkMiss savableNanos=${walkRepeatNanos / 1_000_000}ms\n" +
            "LIVE walkMemo served (walks skipped): $walkMemoServed\n" +
            "walkMiss split: cold=$walkMissCold epochInvalidated=${walkMissEpochIdentical + walkMissEpochStructural + walkMissEpochDiff}" +
            " (identical=$walkMissEpochIdentical structural=$walkMissEpochStructural diff=$walkMissEpochDiff" +
            " meanEpochDelta=${epochDeltaMean()})\n" +
            "epochBumps: ${topCounts(epochBumps, 8)}\n" +
            "epochBlame (last bump before an invalidated repeat): ${topCounts(epochBlame, 8)}\n" +
            "epochNoops (same-value assignments, bump SKIPPED): ${topCounts(epochNoops, 8)}\n" +
            "depKeyed shadow: serve=${depServeIdentical + depServeStructural + depServeWrong}" +
            " (identical=$depServeIdentical structural=$depServeStructural WRONG=$depServeWrong)" +
            " cold=$depCold invalidated=$depInvalidated noPath=$depNoPath\n" +
            "depInvalidatedBy: ${topCounts(depInvalidatedBy, 6)}\n" +
            "depWrongBy: ${topCounts(depWrongBy, 10)}\n" +
            "exprResultKinds: ${topCounts(exprResultKind, 8)}\n" +
            "unstable-kind repeats (the canonical-output prize): structural=$unstableRepeatStructural" +
            " diff=$unstableRepeatDiff cold=$unstableRepeatCold\n" +
            "  structural by kind: ${topCounts(unstableStructuralBy, 8)}\n" +
            depWrongSamples.joinToString("") { "   wrongSample: $it\n" } +
            "narrowWalk cost distribution: " +
                (0 until 4).joinToString(" ") {
                    "${arrayOf("<10us", "<100us", "<1ms", ">=1ms")[it]}=" +
                        "${narrowWalkBucketCalls[it]}/${narrowWalkBucketNanos[it] / 1_000_000}ms"
                } + "\n" +
            "narrowWalk visits: all=$narrowWalkAllVisits huge(>=1ms)=$narrowWalkHugeVisits " +
                "max=$narrowWalkHugeVisitsMax\n" +
            "narrowWalk tripped: $narrowWalkTripped walks, ${narrowWalkTrippedNanos / 1_000_000}ms; " +
                ">=1ms by kind: ${topCounts(narrowWalkHugeByKind, 10)}\n" +
            "time split: narrowWalks=${narrowWalkNanos / 1_000_000}ms typeOfExpr(total incl. nested)=${typeOfExprNanos / 1_000_000}ms " +
                "relations(depth0)=${relationNanos / 1_000_000}ms typeNode(depth0)=${typeNodeNanos / 1_000_000}ms memberResolve(depth0)=${memberResolveNanos / 1_000_000}ms\n" +
            "exprMemo would-save: ${exprSavableNanos / 1_000_000}ms over $exprSavableCalls outermost served calls\n" +
            "shadowMemo: hitCorrect=$shadowMemoHitCorrect hitWRONG=$shadowMemoHitWrong miss=$shadowMemoMiss\n" +
            "typeOfExpr repeats: same-result=$typeOfExprRepeatSame diff-result=$typeOfExprRepeatDiff " +
                "(memoizable fraction of repeats: ${if (typeOfExprRepeatSame + typeOfExprRepeatDiff > 0) typeOfExprRepeatSame * 100 / (typeOfExprRepeatSame + typeOfExprRepeatDiff) else 0}%)\n" +
            "getTypeOfExpression: $getTypeOfExpressionCalls calls, ~$distinct distinct nodes " +
                "(recompute ~x${factor / 10}.${factor % 10}; distinct is pos-keyed, " +
                "cross-file collisions undercount it)"
        )
        val outsideExpr = getTypeOfExpressionByPass[OUTSIDE_PASS] ?: 0L
        appendLine("getTypeOfExpression outside init dispatch: $outsideExpr calls")
        dumpCallerAttribution(appendLine)
        val typeNodeTotal = typeNodeCacheable + typeNodeBypassed
        val bypassPct = if (typeNodeTotal > 0) typeNodeBypassed * 1000 / typeNodeTotal else 0L
        appendLine(
            "getTypeFromTypeNode: cacheable $typeNodeCacheable (hits $typeNodeCacheHits, " +
                "misses ${typeNodeCacheable - typeNodeCacheHits}) vs bypassed $typeNodeBypassed " +
                "(${bypassPct / 10}.${bypassPct % 10}% of resolutions bypass the cache)"
        )
        run {
            val rejects = mappedRejectUnindexed + mappedRejectNoOwner + mappedRejectForeignFile
            val keyed = mappedHits + mappedMisses
            val hitPct = if (keyed > 0) mappedHits * 1000 / keyed else 0L
            val foreignPct = if (typeNodeBypassed > 0) mappedRejectForeignFile * 1000 / typeNodeBypassed else 0L
            appendLine(
                "  INV.5(c) mappedNodeTypes: keyed $keyed (hits $mappedHits = " +
                    "${hitPct / 10}.${hitPct % 10}%, misses $mappedMisses) | " +
                    "gate-rejected $rejects [foreign-file $mappedRejectForeignFile = " +
                    "${foreignPct / 10}.${foreignPct % 10}% of bypassed, unindexed " +
                    "$mappedRejectUnindexed, no-owner $mappedRejectNoOwner]"
            )
            if (verifyMappedCache) {
                appendLine(
                    "  INV.5(c2) VERIFY: served-wrong-SHAPE $mappedServeWrong " +
                        "(a missing key dimension) | served-id-ONLY $mappedServeIdOnly " +
                        "(structurally identical, non-canonical identity)"
                )
            }
            run {
                appendLine("  INV.5(c4) context fingerprint BUILDS: $ctxFingerprintBuilds")
                appendLine(
                    "  INV.5(c5) bypassed-resolution PRIZE: ${bypassedResolveNanos / 1_000_000} ms " +
                        "over $bypassedResolveOutermost outermost calls " +
                        "(${if (bypassedResolveOutermost > 0) bypassedResolveNanos / bypassedResolveOutermost else 0} ns each)"
                )
            }
        }
        appendLine(
            "SPINE attribution: nodes=$spineNodes " +
                "enter=${spineEnterNanos / 1_000_000}ms leave=${spineLeaveNanos / 1_000_000}ms " +
                "scope=${spineScopeNanos / 1_000_000}ms ures=${spineUResNanos / 1_000_000}ms " +
                "forEachChild=${spineChildrenNanos / 1_000_000}ms"
        )
        if (spineKindCount.isNotEmpty()) {
            appendLine("  per-kind enter+leave (top 12 by total ms):")
            spineKindNanos.entries.sortedByDescending { it.value }.take(12).forEach { (k, ns) ->
                val c = spineKindCount[k] ?: 1L
                appendLine(
                    "    kind $k: ${ns / 1_000_000} ms over $c nodes = ${ns / c} ns/node"
                )
            }
        }
        appendLine(
            "flow-narrowing walks: $narrowWalks " +
                "(outside init dispatch: ${narrowWalksByPass[OUTSIDE_PASS] ?: 0L})"
        )
        appendLine(
            "pre-parse reuse (INV.1(e), multi-file core): reused $preParseReused, " +
                "parsed fresh $preParseFresh"
        )
        if (globalsLookups > 0) {
            appendLine("== globals lookups (INV.3(a)) ==")
            appendLine(
                "total $globalsLookups: trueGlobal $globalsTrueGlobalHits, " +
                    "shared $globalsSharedHits, ownLocal $globalsOwnLocalHits, " +
                    "CONFLATED $globalsConflatedHits, unscoped $globalsUnscopedHits, " +
                    "miss $globalsMisses"
            )
            fun top(map: Map<String, Long>, n: Int, label: String) {
                if (map.isEmpty()) return
                appendLine("$label (top ${minOf(n, map.size)} of ${map.size}):")
                for ((k, v) in map.entries.sortedByDescending { it.value }.take(n)) {
                    appendLine("  ${v.toString().padStart(9)}  $k")
                }
            }
            top(globalsConflatedByName, 30, "conflated by name")
            top(globalsConflatedByPass, 20, "conflated by pass")
            top(globalsUnscopedByName, 30, "unscoped by name")
            top(globalsUnscopedByPass, 20, "unscoped by pass")
        }
    }

    private fun ms(nanos: Long): String {
        val tenths = nanos / 100_000
        return "${tenths / 10}.${if (tenths < 0) (-tenths) % 10 else tenths % 10}"
    }
}

/**
 * INV.0: wrap one named checker-init pass. When [PassTiming.enabled] is off
 * this is a plain indirect call (a field read + lambda invocation — ~513 total
 * per compile, unmeasurable); when on, it accumulates the pass's wall time and
 * exposes the pass name for counter attribution.
 *
 * Deliberately NOT inline: the checker `init` dispatch wraps ~513 call sites,
 * and inlining the try/finally + time-mark body at each would bloat the
 * constructor toward the JVM's 64 KB method limit.
 */
internal fun pass(name: String, body: () -> Unit) {
    // M0.1 tail-triage lab: batch-disable (experiment-only; empty by default).
    if (PassTiming.disabledPasses.isNotEmpty() && name in PassTiming.disabledPasses) return
    if (!PassTiming.enabled) {
        if (PassTiming.censusMode) {
            // Light census: emitted-diagnostic delta only (no nanos/sets),
            // into the reset-immune process-lifetime accumulator.
            val c0 = PassTiming.diagnosticsSize?.invoke() ?: 0
            try {
                body()
            } finally {
                val c1 = PassTiming.diagnosticsSize?.invoke() ?: 0
                if (c1 > c0) {
                    PassTiming.censusByPass[name] = (PassTiming.censusByPass[name] ?: 0) + (c1 - c0)
                }
            }
        } else {
            body()
        }
        return
    }
    val saved = PassTiming.currentPass
    PassTiming.currentPass = name
    val d0 = PassTiming.diagnosticsSize?.invoke() ?: 0
    val mark = TimeSource.Monotonic.markNow()
    try {
        body()
    } finally {
        PassTiming.notePass(name, mark.elapsedNow().inWholeNanoseconds)
        val d1 = PassTiming.diagnosticsSize?.invoke() ?: 0
        if (d1 > d0) {
            PassTiming.diagsByPass[name] = (PassTiming.diagsByPass[name] ?: 0) + (d1 - d0)
        }
        PassTiming.currentPass = saved
    }
}

/**
 * INV.3(a): how a keyed `globals` lookup relates to the per-file visibility
 * model INV.3 migrates to (own locals + true globals = lib + script-file
 * locals + augmentation-added names).
 *
 *  - [MISS] — the name is not in `globals` at all.
 *  - [TRUE_GLOBAL] — no module file declares the name: presence is legitimate
 *    (lib / script-file / augmentation) and survives the conflation retirement.
 *  - [SHARED] — a module file declares the name AND it has a legitimate
 *    non-module meaning: presence survives, but the merged symbol is polluted
 *    by the module declarations (the chimera/per-file-view dimension).
 *  - [OWN_LOCAL] — only module files declare it, and it is the CURRENT check
 *    file's own top-level local: a per-file scope serves this site unchanged.
 *  - [CONFLATED] — only module files declare it and it is NOT the current
 *    file's local: the site depends on the cross-file leak (the migration
 *    worklist proper).
 *  - [UNSCOPED] — only module files declare it, but the site has no file
 *    context (`currentFileLocals == null`), so own-vs-foreign is undecidable —
 *    also a datum for INV.4 (how much checking runs without file attribution).
 */
internal enum class GlobalsLookupClass { MISS, TRUE_GLOBAL, SHARED, OWN_LOCAL, CONFLATED, UNSCOPED }

/**
 * INV.3(a): a [SymbolTable] that reports keyed lookups (`get`/`containsKey`)
 * to [onLookup] while delegating everything else to [backing] — which stays
 * the exact `symbolTable()` LinkedHashMap, so iteration order (and therefore
 * every order-sensitive consumer) is byte-identical to the uninstrumented map.
 *
 * The checker constructs its `globals` as this class ONLY when
 * [PassTiming.enabled] is on at construction time; the default path keeps the
 * plain map, so a disabled run has zero added code on the hottest map in the
 * program. [onLookup] stays null until the checker's init has finished the
 * globals merge and installed the classifier (pre-install lookups are the
 * merge's own bookkeeping, not consultation).
 *
 * NOTE: interface delegation does not forward `equals`/`hashCode`/`toString`
 * — this table has identity semantics. `globals` is never compared or
 * rendered, only consulted; do not reuse this wrapper for a map that is.
 */
internal class InstrumentedSymbolTable(
    private val backing: SymbolTable = symbolTable(),
) : SymbolTable by backing {

    /** Classifier installed by the checker; null = inert. */
    var onLookup: ((name: String, result: Symbol?) -> Unit)? = null

    override fun get(key: String): Symbol? {
        val result = backing[key]
        onLookup?.invoke(key, result)
        return result
    }

    override fun containsKey(key: String): Boolean {
        val result = backing[key]
        onLookup?.invoke(key, result)
        return result != null
    }

    /** A keyed read WITHOUT classification — for a consult a migrated per-file
     *  gate (`Checker.globalsForFile`) has ALREADY proven per-file-visible: the
     *  conflated/own-local tables must keep measuring only UN-migrated traffic,
     *  and a node-keyed flip's legitimate foreign-node hit would otherwise
     *  classify CONFLATED against the CHECKING file's locals (INV.3(c)(ii)). */
    fun getUnclassified(key: String): Symbol? = backing[key]
}
