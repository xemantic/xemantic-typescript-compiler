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

    /** Master switch — off by default. Every hook below is inert when false.
     *
     *  The setter MAINTAINS the two derived tier flags below; see them for why
     *  they are fields rather than `get() = enabled && detail` getters. */
    var enabled: Boolean = false
        set(v) {
            field = v
            detailed = v && detail
            spineProfiled = v && spineDetail
        }

    // -----------------------------------------------------------------------
    // (WARM.1)(c) round 846 — the probe's THREE TIERS.
    //
    // `--passTiming` costs ~2,840 ms on a COLD compile (+12.4%) and 3,450-3,945
    // ms on a WARM one (+50-55%, docs/perf/warm-jvm-attribution.md § 3): roughly
    // the same MILLISECONDS in both regimes, so warm it DOMINATES the table it
    // produces and no warm row's absolute can size a lever. The cost is not in
    // the ~513 `pass()` boundaries — it is in the per-CALL bookkeeping those two
    // flags below gate: 574,620 `getTypeOfExpression` hooks (a distinct-keyed
    // HashSet, a by-pass HashMap, a shadow-memo probe and a timestamp pair
    // EACH), ~2 M classified `globals` lookups, every fenced-setter no-op, the
    // narrowing-walk shadow, and a per-node profiled spine walk that keeps two
    // BOXED `HashMap<Int, Long>` entries per node over 856,962 nodes.
    //
    // Turning them off leaves the pass ROWS — which is the whole per-pass table
    // — measured at ~513 boundaries per compile. Both default to TRUE, so
    // `--passTiming` and every gate that reads its counters (`cost_gate.py`,
    // Inv0PassTimingTest, Inv3GlobalsLookupTest) are unchanged; the tiers are
    // opt-OUT, via `--passTimingRows` / `--passTimingSpine`.
    //
    // They are MODES, not counters: [reset] must never clear them (same
    // contract as [verifyMappedCache] / [callerAttr]).
    // -----------------------------------------------------------------------

    /** Tier 3 — the per-CALL counters, sets and shadow memos (type-system
     *  hooks, classified globals lookups, epoch no-ops, narrowing shadow, node
     *  kind histogram). Off ⇒ every such counter stays 0 and MUST NOT be read
     *  as a measurement; [dump] says so in its header. */
    var detail: Boolean = true
        set(v) { field = v; detailed = enabled && v }

    /** Tier 2 — the per-NODE profiled spine walk ([spineEnterNanos] & co and
     *  the per-kind maps). Off ⇒ `checkSpine` runs the PRODUCTION walk and its
     *  `pass()` row is un-perturbed; the SPINE sub-rows stay 0. */
    var spineDetail: Boolean = true
        set(v) { field = v; spineProfiled = enabled && v }

    /** True iff the tier-3 per-call hooks should run — i.e. [enabled] AND
     *  [detail].
     *
     *  A maintained FIELD, not `get() = enabled && detail`: this is read on the
     *  PRODUCTION path a few million times per compile (every
     *  `getTypeOfExpression`, every type-node resolution, every fenced setter),
     *  and a conjunction getter would double the accessor calls a production
     *  run pays for the probe it is not running. Maintenance lives in the three
     *  setters above; nothing else may assign it, which is what keeps it from
     *  going stale. */
    var detailed: Boolean = false
        private set

    /** True iff the tier-2 profiled spine walk should run. Maintained exactly
     *  as [detailed]. */
    var spineProfiled: Boolean = false
        private set

    /** `full` / `spine` / `rows` — printed in [dump]'s header so a table can
     *  never be mistaken for a tier that measured more than it did. */
    fun tierName(): String = when {
        detail && spineDetail -> "full"
        detail -> "full-noSpine"
        spineDetail -> "spine"
        else -> "rows"
    }

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

    /**
     * (INC.17) THE RE-ENTRANT REPLAY'S FILTER — when non-null, [pass] runs the
     * body of a registration ONLY when its name is in this set, and skips every
     * other one.
     *
     * This is what lets `Checker.recheckAdditionalFiles` re-enter the whole
     * `init` dispatch SEQUENCE — `initSetupPasses()` then `initCheckPasses1()`
     * through `initCheckPasses8()`, verbatim, in source order — and have only
     * the partition-DEPENDENT rows execute. Re-entering the sequence rather
     * than re-listing the dependent passes is the whole point: a hand-kept list
     * of "the passes to replay, in order" is CLAUDE.md's mirrored-list hazard
     * (`ccetPrologueMayFire`), and it would drift silently the first time a pass
     * is added, moved or renamed.
     *
     * SEQUENTIAL PATH ONLY, and set/restored inside one call. `Checker` refuses
     * to arm a recheck beside a partition worker, so no `--workers` run can
     * observe it. Deliberately NOT routed through the round-848 `ModeLedger`:
     * that ledger is for CLI flags a `--serve` daemon must restore between
     * requests, and this is neither a flag nor observable outside one method.
     */
    var replayPasses: Set<String>? = null

    /**
     * (INC.17) EXPERIMENT ARM — when true, `Checker.recheckAdditionalFiles` arms NO
     * filter and re-enters EVERY `init` pass.
     *
     * It exists to attribute a divergence rather than to be shipped. A replay that
     * diverges from a fresh narrowed build has two candidate causes and they call
     * for opposite work: the CLASSIFICATION is starving a pass that should have
     * re-entered (fix the classification), or the checker's caches simply carry the
     * seed build's first-touch order (the (INC.2)/(INC.5) order-dependence, which no
     * classification can reach). Running with this on answers which: a divergence
     * that SURVIVES a full re-entry is not a classification defect.
     *
     * Like [disabledPasses] this deliberately changes behaviour — any run using it
     * is an experiment, never a gate — and it is off in every shipped path.
     */
    var replayAllPasses: Boolean = false

    /**
     * (INC.19) ATTRIBUTION-ONLY LEVER — extra `init` pass names UNIONED into the
     * set `Checker.recheckAdditionalFiles` re-enters.
     *
     * (INC.17)'s replay classifies a pass as partition-DEPENDENT by whether it
     * READ the partition, where soundness wants "its OUTPUT depends on the
     * partition"; the two come apart at every producer/consumer pair, and the
     * measured residue is 8 of 75 files rendering a type parameter without its
     * constraint. This is the bisection handle for finding WHICH pass closes that
     * gap: add candidates, re-run `scripts/replay-differential.sh`, see which
     * addition repairs the divergence.
     *
     * MUST default empty, and empty is behaviour-free by construction: the union
     * with an empty set is the classified set, so no shipped path can observe it
     * (INV.0's rule — a probe that is off must not change what the compiler does).
     * Like [disabledPasses] and [replayAllPasses], any run with it non-empty is an
     * experiment, never a gate.
     */
    var replayExtraPasses: Set<String> = emptySet()

    /**
     * (INC.19) When true, [pass] records EVERY registration name it is handed into
     * [registeredPasses], filters included — so a driver can print the candidate
     * universe (`all - replayed`) instead of guessing it from a source grep, which
     * over-counts (a `pass("...")` inside a KDoc, and registrations behind option
     * branches that never run).
     *
     * Off by default; on it is one `LinkedHashSet.add` per registration and it
     * changes nothing the compiler decides.
     */
    var recordRegistrations: Boolean = false

    /** (INC.19) Every pass name [pass] was handed while [recordRegistrations] was
     *  on, in first-registration order (= the `init` dispatch order). */
    val registeredPasses = LinkedHashSet<String>()

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

    /**
     * (INC.17) PARTITION CENSUS — which `init` passes ever READ the partition.
     *
     * A pass whose loops iterate [Checker.binderResults] is partition-INVARIANT
     * by construction: its work is a function of the PROGRAM, so a checker that
     * has already run it once need not replay it to answer a question about a
     * file it did not originally check. A pass that reaches `checkedResults` (or
     * `assignedFileNames` directly) is partition-DEPENDENT and must replay.
     *
     * The classification is taken at RUN TIME rather than by a source analyzer,
     * for two reasons CLAUDE.md records the hard way: a Kotlin comment/string
     * stripper over `Checker.kt` desynchronises on `'\''` and on `${'$'}{…}`
     * templates and then reports a confident "no hazard" over an EMPTY closure,
     * and the `pass("name") { … }` sample in a KDoc parses as a real
     * registration. A getter on the property itself cannot be wrong about who
     * read it.
     *
     * Attribution is to [currentPass], i.e. the INNERMOST enclosing wrapped
     * pass — reads made outside any pass land in [partitionReadsOutsidePass].
     * Recorded only while [enabled]; off it is one static boolean read.
     */
    val partitionReadsByPass = LinkedHashMap<String, Long>()

    /** (INC.17) partition reads made with no wrapped pass on the stack. */
    var partitionReadsOutsidePass: Long = 0

    /**
     * (INC.17) SIGNED diagnostics delta per pass — [diagsByPass] clamps to
     * positive deltas, so a pass that RETRACTS (`diagnostics.removeAll { … }`)
     * is invisible there and looks exactly like a silent one. A negative net
     * here names a retractor, which is hazard (a) of the re-entrant replay: the
     * `init` run-8 retractions only work after run 1 emitted, so a replay may
     * not reset the diagnostics list to an arbitrary prefix.
     */
    val diagNetByPass = LinkedHashMap<String, Int>()

    /** (INC.17) record one read of the partition view. See [partitionReadsByPass]. */
    fun notePartitionRead() {
        val name = currentPass
        if (name == null) partitionReadsOutsidePass++
        else partitionReadsByPass[name] = (partitionReadsByPass[name] ?: 0L) + 1L
    }

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

    /** `getTypeOfSymbol` resolutions whose answer was PERSISTED into the
     *  global `symbolTypes` cache (the caller-supplied instantiation context
     *  was empty, so the answer is a function of the symbol alone). */
    var symbolTypeCached: Long = 0

    /** `getTypeOfSymbol` resolutions NOT persisted, because a caller-supplied
     *  instantiation context (type-param scope / alias args / inference
     *  namespace) was active and the answer therefore depends on WHO asked —
     *  i.e. on program order. See (ORDER.1). */
    var symbolTypeContextBypassed: Long = 0

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
        symbolTypeCached = 0
        symbolTypeContextBypassed = 0
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
        cmamAnyOpenings = 0
        cmamAnyNanos = 0
        cmamAnyNarrowed = 0
        cmamAnyAccepted = 0
        cmamAnyNanosNarrowed = 0
        cmamAnyWalkNanos = 0
        cmamAnyWalkNanosNarrowed = 0
        cmamAnyPreRefused = 0
        cmamAnyPreRefusedNoPath = 0
        cmamAnyPreRefusedNarrowed = 0
        cmamAnyPreRefusedAccepted = 0
        cmamAnyPreRefusedNanos = 0
        cmamAnyPreRefusedWalkNanos = 0
        cmamAnyPreNanos = 0
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
        partitionReadsByPass.clear()
        partitionReadsOutsidePass = 0
        diagNetByPass.clear()
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

    // -----------------------------------------------------------------------
    // (NARROW.2)(e) round 854 — the price of round 852's narrowed-`any`
    // receiver opening, measured IN SITU.
    //
    // Round 853 attributed `narrow.walks` 17,851 -> 31,961 (+79.04%) to that one
    // opening by ablation, and a COUNT is not a COST (round 801). The obvious
    // instrument — difference `narrowWalks=<ms>` between an ablated build and
    // HEAD — cannot answer it: that column reads 1,423 / 1,460 / 1,602 ms across
    // three runs of the SAME binary (+-6-9%), which is larger than the object.
    // So the span is taken where the cost is incurred instead: one timestamp
    // pair around `cmamNarrowedAnyReceiverType`'s call to
    // `getNarrowedTypeForReference`, which is the whole of what the opening
    // added. That is ~14 k pairs at ~86 ns (round 734's differential figure),
    // i.e. ~1 ms of self-cost against the hundreds it is measuring.
    //
    // The three counters are the produced-vs-consumed ratio round 801 demands
    // BEFORE any timing is read as a prize: how often the opening runs, how
    // often the flow answered something different from `any`, and how often the
    // result survived every refusal and became a receiver type.
    // -----------------------------------------------------------------------

    /** Calls to `cmamNarrowedAnyReceiverType` that reached the flow read — i.e.
     *  an `any`-typed receiver in a file with a flow graph. The population the
     *  opening pays for. */
    var cmamAnyOpenings: Long = 0

    /** Nanos spent inside those `getNarrowedTypeForReference` calls, INCLUSIVE
     *  of the memo consult and of any walk they launched. This is the number a
     *  perfect pre-test would return. */
    var cmamAnyNanos: Long = 0

    /** Of [cmamAnyOpenings], those where the flow answered something OTHER than
     *  the declared `any` — the PRODUCED side. */
    var cmamAnyNarrowed: Long = 0

    /** Of [cmamAnyNarrowed], those that survived every refusal below it and
     *  became the receiver type — the CONSUMED side. A wide gap between this
     *  and [cmamAnyOpenings] is what makes a cheaper pre-test worth designing. */
    var cmamAnyAccepted: Long = 0

    /**
     * [cmamAnyNanos] restricted to the openings that DID narrow.
     *
     * The split, not the total, is what a pre-test is worth: a gate that refuses
     * the receivers the flow was never going to narrow can only ever return
     * `cmamAnyNanos - cmamAnyNanosNarrowed`, and a population being 92% of the
     * COUNT says nothing about its share of the COST (round 759 — the cheap-tail
     * law runs BOTH ways, and assuming the direction cost two rounds their
     * predictions).
     */
    var cmamAnyNanosNarrowed: Long = 0

    /**
     * The same span measured as the delta of [narrowWalkNanos] — i.e. the WALK
     * ONLY, excluding the tier-3 shadow-memo bookkeeping (`walkShadow.put`, the
     * union-id sort, `depKeyedShadowClassify`, the bucket hooks) that sits
     * inside [cmamAnyNanos] but does not exist in a production run.
     *
     * [cmamAnyNanos] is therefore an UPPER bound on the production cost and this
     * is the representative one; quoting the first as the price would repeat
     * round 734's over-read of a probe boundary in a new costume.
     */
    var cmamAnyWalkNanos: Long = 0

    /** [cmamAnyWalkNanos] restricted to the openings that DID narrow. */
    var cmamAnyWalkNanosNarrowed: Long = 0

    // -----------------------------------------------------------------------
    // (NARROW.2)(f) round 855 — the PRE-TEST census, which HONOURS NOTHING.
    //
    // The candidate gate (`FlowGraph.narrowableRoots()`) is evaluated at every
    // opening and its verdict is RECORDED; the walk then runs regardless, so a
    // probe run's diagnostics are byte-identical to a production one and the
    // yield is measured against the very population round 854 priced.
    //
    // What the numbers have to say before a gate may be built:
    //  - YIELD:     [cmamAnyPreRefusedWalkNanos] against the never-narrowed
    //               cost (`cmamAnyWalkNanos - cmamAnyWalkNanosNarrowed`). The
    //               go/no-go is ~70% — below it the realisable saving lands
    //               inside the +-1.0% warm A/B band.
    //  - SOUNDNESS: [cmamAnyPreRefusedAccepted] must be ZERO — no opening that
    //               produced a receiver type may be refused. This is a claim a
    //               probe can check exhaustively; a design argument cannot.
    //  - CONTROL:   round 790 — a verifier reading 0 proves nothing until its
    //               COMPLEMENT population is shown to be non-empty, so the
    //               kept openings' narrow count is printed beside the refused
    //               ones', and [cmamAnyPreRefused] itself must be non-zero.
    // -----------------------------------------------------------------------

    /** Openings the candidate pre-test would refuse (walk skipped). */
    var cmamAnyPreRefused: Long = 0

    /** Of [cmamAnyPreRefused], those refused because the receiver has no
     *  reference PATH at all — `getNarrowedTypeForReference` returns the declared
     *  type immediately for those, so they are FREE refusals and must not be read
     *  as yield. The discriminating population is the remainder. */
    var cmamAnyPreRefusedNoPath: Long = 0

    /** Of [cmamAnyPreRefused], those that nevertheless narrowed — the soundness
     *  leak of a name-keyed superset (the name-INDEPENDENT `never` arms). */
    var cmamAnyPreRefusedNarrowed: Long = 0

    /** Of [cmamAnyPreRefused], those that were ACCEPTED as a receiver type. This
     *  is the one that must be zero: it is the only refusal that changes what the
     *  compiler emits. */
    var cmamAnyPreRefusedAccepted: Long = 0

    /** [cmamAnyNanos] / [cmamAnyWalkNanos] restricted to the refused openings —
     *  what a gate would actually return. */
    var cmamAnyPreRefusedNanos: Long = 0
    var cmamAnyPreRefusedWalkNanos: Long = 0

    /** The pre-test's OWN cost, measured at every opening: what the gate would
     *  spend to earn [cmamAnyPreRefusedWalkNanos]. */
    var cmamAnyPreNanos: Long = 0

    fun noteCmamAnyOpening(
        nanos: Long,
        walkNanos: Long,
        narrowed: Boolean,
        preRefused: Boolean = false,
        preRefusedNoPath: Boolean = false,
        preNanos: Long = 0,
    ) {
        cmamAnyOpenings++
        cmamAnyNanos += nanos
        cmamAnyWalkNanos += walkNanos
        cmamAnyPreNanos += preNanos
        if (narrowed) {
            cmamAnyNarrowed++
            cmamAnyNanosNarrowed += nanos
            cmamAnyWalkNanosNarrowed += walkNanos
        }
        if (preRefused) {
            cmamAnyPreRefused++
            cmamAnyPreRefusedNanos += nanos
            cmamAnyPreRefusedWalkNanos += walkNanos
            if (preRefusedNoPath) cmamAnyPreRefusedNoPath++
            if (narrowed) cmamAnyPreRefusedNarrowed++
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
        appendLine("tier: ${tierName()} (detail=$detail spineDetail=$spineDetail)")
        if (!detail) {
            appendLine(
                "  !! TIER '${tierName()}' — the per-call counters below are NOT COLLECTED. " +
                    "Every counter reading 0 is an ABSENT measurement, not a measured zero. " +
                    "The pass ROWS (and checker-init total) ARE measured, at ~${passCalls.size} " +
                    "boundaries; that is the point of this tier (WARM.1)(c)."
            )
        }
        if (!spineDetail) {
            appendLine(
                "  !! the SPINE sub-rows (enter/leave/scope/ures/forEachChild and the per-kind " +
                    "table) are NOT COLLECTED — `checkSpine` ran the PRODUCTION walk."
            )
        }
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
            "cmamNarrowedAny (NARROW.2)(e): openings=$cmamAnyOpenings " +
                "narrowed=$cmamAnyNarrowed accepted=$cmamAnyAccepted " +
                "span=${cmamAnyNanos / 1_000_000}ms (of which narrowed " +
                "${cmamAnyNanosNarrowed / 1_000_000}ms) walkOnly=" +
                "${cmamAnyWalkNanos / 1_000_000}ms (of which narrowed " +
                "${cmamAnyWalkNanosNarrowed / 1_000_000}ms)\n" +
            "cmamAnyPreTest (NARROW.2)(f): refused=$cmamAnyPreRefused " +
                "(noPath=$cmamAnyPreRefusedNoPath) kept=${cmamAnyOpenings - cmamAnyPreRefused} " +
                "refusedNarrowed=$cmamAnyPreRefusedNarrowed " +
                "refusedAccepted=$cmamAnyPreRefusedAccepted " +
                "keptNarrowed=${cmamAnyNarrowed - cmamAnyPreRefusedNarrowed} " +
                "refusedSpan=${cmamAnyPreRefusedNanos / 1_000_000}ms " +
                "refusedWalkOnly=${cmamAnyPreRefusedWalkNanos / 1_000_000}ms " +
                "preTestCost=${cmamAnyPreNanos / 1_000_000}ms\n" +
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
            val symTotal = symbolTypeCached + symbolTypeContextBypassed
            val pct = if (symTotal > 0) symbolTypeContextBypassed * 1000 / symTotal else 0L
            appendLine(
                "getTypeOfSymbol cache writes: persisted $symbolTypeCached vs " +
                    "context-bypassed $symbolTypeContextBypassed " +
                    "(${pct / 10}.${pct % 10}% of first touches are order-dependent)"
            )
        }
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
        if (GlobalsAmp.calls > 0) appendLine(GlobalsAmp.report())
        if (SpineAmp.nodes > 0) appendLine(SpineAmp.report())
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
    // (INC.55) the coarse cancellation poll: ~480 boundaries per compile, spread
    // through the whole init dispatch. `check` is a single field read when no host
    // has armed one, which is every non-embedding build.
    Cancellation.check()
    // (INC.19) the candidate universe for the replay bisection. Off by default and
    // recorded ABOVE every filter, so a replay's own run still names every row.
    if (PassTiming.recordRegistrations) PassTiming.registeredPasses.add(name)
    // M0.1 tail-triage lab: batch-disable (experiment-only; empty by default).
    if (PassTiming.disabledPasses.isNotEmpty() && name in PassTiming.disabledPasses) return
    // (INC.17) the re-entrant replay re-enters the whole dispatch SEQUENCE and
    // lets only the partition-dependent rows run. Null on every ordinary compile.
    PassTiming.replayPasses?.let { if (name !in it) return }
    if (!PassTiming.enabled) {
        // (INC.17) `currentPass` is maintained UNCONDITIONALLY: the partition-read
        // recording that classifies a pass as dependent runs on an ordinary
        // (instrumentation-free) build, because that is the build whose partition a
        // recheck widens. Two field writes per registration, ~513 per compile.
        val savedName = PassTiming.currentPass
        PassTiming.currentPass = name
        try {
            if (PassTiming.censusMode) {
                // Light census: emitted-diagnostic delta only (no nanos/sets),
                // into the reset-immune process-lifetime accumulator.
                val c0 = PassTiming.diagnosticsSize?.invoke() ?: 0
                try {
                    body()
                } finally {
                    val c1 = PassTiming.diagnosticsSize?.invoke() ?: 0
                    if (c1 > c0) {
                        PassTiming.censusByPass[name] =
                            (PassTiming.censusByPass[name] ?: 0) + (c1 - c0)
                    }
                }
            } else {
                body()
            }
        } finally {
            PassTiming.currentPass = savedName
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
        // (INC.17): the SIGNED net, so a RETRACTING pass is not clamped away.
        if (d1 != d0) {
            PassTiming.diagNetByPass[name] =
                (PassTiming.diagNetByPass[name] ?: 0) + (d1 - d0)
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
/**
 * (AUDIT.3) round 759 — the opt-in AMPLIFIED price of one `globals[name]`
 * probe, closing the last asserted-never-measured population in
 * `docs/ARCHITECTURE-RETHINK.md` § 0 ("961,213 globals lookups at 98.1% miss …
 * priced ≲0.2%"). The COUNT has always been measured and is a cost-gate
 * counter; the TIME behind it never was.
 *
 * ## Why amplification rather than a nested span
 *
 * The arc's own instrument does not work here. A `nowNanos()` pair costs
 * 86–92 ns (measured independently by rounds 734 and 735) and the thing being
 * measured is expected to cost ~57 ns, so a per-lookup span would report
 * roughly 2.5× the truth and its error bar would exceed the answer. Round 736
 * hit the same wall inside the narrowing walk and escaped it with counters;
 * counters cannot price a `HashMap` probe, so this escapes the other way —
 * **amplify the signal instead of shrinking the instrument.**
 *
 * With [reads] `= r`, one pair brackets `r` reads of the same key. The reported
 * per-lookup figure is then `p(r) = cold + (r-1)*warm + b`, where `b` is the
 * unknown pair cost. **Two runs at different `r` eliminate `b` entirely**:
 *
 * ```
 * warm = (p(r2) - p(r1)) / (r2 - r1)          // b cancels
 * cold = p(r1) - (r1 - 1) * warm - b          // needs b, but weakly
 * ```
 *
 * `cold` is the number the population wants — the first read of a key at that
 * moment in the compile, which is exactly what production performs. `warm` is
 * the same read with the entry already in L1; the gap between them is a
 * measurement in its own right, and it is also this instrument's
 * **falsification**: if the loop were elided the reported `p(r)` would not grow
 * with `r` at all, and the extra reads must additionally show up in WALL time.
 *
 * Off in production by construction: [InstrumentedSymbolTable] is only ever
 * constructed under `--passTiming` (`Checker.globals`), and with [reads] `== 0`
 * its read path is unchanged.
 */
object GlobalsAmp {

    /**
     * Reads performed per lookup under ONE timestamp pair; `0` = OFF.
     *
     * A NEGATIVE value is the in-situ EMPTY bracket — the pair with no read
     * between it, which prices the instrument itself at the same site and
     * frequency. Read its answer with the codebase's own warning in hand: an
     * in-situ empty span has over-read the differential by 3.6x and 4.4x in
     * rounds 734 and 735, so it bounds the pair rather than measuring it.
     */
    var reads: Int = 0

    var nanos: Long = 0
    var calls: Long = 0

    /** Consumes the amplified results so the JIT cannot elide the reads. */
    var sink: Long = 0

    fun reset() {
        nanos = 0
        calls = 0
        sink = 0
    }

    fun report(): String = buildString {
        appendLine("== (AUDIT.3) amplified globals-probe price ==")
        appendLine(
            "reads per lookup: $reads   bracketed lookups: $calls   " +
                "total ${nanos / 1_000_000} ms   sink $sink"
        )
        val per = if (calls > 0) nanos / calls else 0L
        appendLine(
            "p($reads) = $per ns per bracketed lookup = cold + ${reads - 1} * warm + boundary"
        )
        appendLine(
            "  solve `warm` from TWO runs at different `reads`: (p(r2) - p(r1)) / (r2 - r1)"
        )
    }
}

internal class InstrumentedSymbolTable(
    private val backing: SymbolTable = symbolTable(),
) : SymbolTable by backing {

    /** Classifier installed by the checker; null = inert. */
    var onLookup: ((name: String, result: Symbol?) -> Unit)? = null

    /**
     * (AUDIT.3): the read the population is made of, optionally AMPLIFIED. A
     * single `nowNanos()` pair costs 86–92 ns (rounds 734/735) against a probe
     * that is expected to cost ~57 ns, so timing one read would be measuring the
     * instrument; [GlobalsAmp] performs `reads` of them under ONE pair instead.
     * Off ([GlobalsAmp.reads] `== 0`) this is `backing[key]` and nothing else.
     */
    private fun timedGet(key: String): Symbol? {
        val r = GlobalsAmp.reads
        if (r == 0) return backing[key]
        if (r < 0) {
            // In-situ EMPTY bracket: price the timestamp pair itself at the same
            // site and frequency, so `cold` can be separated from it. The read
            // still happens, outside the pair, so the compile is unchanged.
            val e0 = PassTiming.nowNanos()
            GlobalsAmp.nanos += PassTiming.nowNanos() - e0
            GlobalsAmp.calls++
            return backing[key]
        }
        val t0 = PassTiming.nowNanos()
        var result: Symbol? = null
        var seen = 0L
        var i = 0
        while (i < r) {
            val v = backing[key]
            // Consume the result: without this the JIT may hoist the loop-invariant
            // read and the probe would report a cost that production never pays.
            if (v != null) seen++
            result = v
            i++
        }
        GlobalsAmp.nanos += PassTiming.nowNanos() - t0
        GlobalsAmp.calls++
        GlobalsAmp.sink += seen
        return result
    }

    override fun get(key: String): Symbol? {
        val result = timedGet(key)
        onLookup?.invoke(key, result)
        return result
    }

    override fun containsKey(key: String): Boolean {
        val result = timedGet(key)
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
