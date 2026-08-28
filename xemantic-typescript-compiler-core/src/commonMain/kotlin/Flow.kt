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

// ---------------------------------------------------------------------------
// Flow nodes — control-flow graph for narrowing
//
// Each AST reference position is associated with a FlowNode that represents
// the program point just before that reference is evaluated. The checker
// (in Phase 17 step 2 — not yet wired) walks back through antecedents to
// determine the narrowed type at that point.
//
// Modeled on TypeScript's `FlowNode` types in src/compiler/types.ts and
// tsgo's internal/checker/flow.go.
// ---------------------------------------------------------------------------

sealed interface FlowNode {
    val id: Int
}

/**
 * The start of a function's flow (or the file's top-level flow).
 *
 * B464 (flow-into-closures): for an ArrowFunction / FunctionExpression body
 * nested inside another function, [outerFlow] points at the enclosing
 * function's flow node at the closure's definition point, so the checker can
 * continue narrowing a captured (closed-over) variable into the closure —
 * matching tsc's "extend the flow container" loop (checker.ts
 * `getFlowTypeOfReference`). The continuation is gated on the captured name:
 *   - it must NOT be a closure local ([localNames]) — a shadowing param/local
 *     gets its own type, not the outer narrowing;
 *   - it must NOT be reassigned at or after the closure's position within the
 *     enclosing function ([reassignedAfterNames]) — tsc's `isPastLastAssignment`
 *     (a variable reassigned after the closure is non-const, so its narrowing
 *     does not flow in; the f4-vs-f5 distinction in `implicitConstParameters`).
 */
class FlowStart(
    override val id: Int,
    val container: Node?,
    val outerFlow: FlowNode? = null,
    val reassignedAfterNames: Set<String> = emptySet(),
    val localNames: Set<String> = emptySet(),
    /** B467: `var`-declared names in the ENCLOSING function scope → their declaration.
     *  tsc does not flow narrowing into a closure for a captured `var` (function-scoped/
     *  hoisted, so the positional `isPastLastAssignment` guarantee doesn't hold) — see
     *  `narrowingPastLastAssignment` f13. let/const/param captures still narrow. The
     *  declaration is retained so the checker can recover the captured var's declared
     *  annotation type (which getTypeOfExpression cannot resolve across the closure). */
    val enclosingVarDecls: Map<String, VariableDeclaration> = emptyMap(),
) : FlowNode

/** Code after return/throw/break/continue — unreachable. */
class FlowUnreachable(override val id: Int) : FlowNode

/**
 * A point reachable from multiple predecessors (e.g. after if/else,
 * end of switch, after try/catch). Antecedents are appended as branches
 * converge during graph construction.
 */
class FlowBranchLabel(
    override val id: Int,
    val antecedents: MutableList<FlowNode> = mutableListOf(),
) : FlowNode

/**
 * A loop's join point (top-of-loop). Has antecedents from the entry and
 * from each `continue` / back-edge after the loop body completes.
 */
class FlowLoopLabel(
    override val id: Int,
    val antecedents: MutableList<FlowNode> = mutableListOf(),
) : FlowNode

/**
 * Flow node after assigning to a variable, property, element, or destructured
 * binding. The `node` is the assignment target (LHS of `=`, or the
 * VariableDeclaration / BindingElement / Parameter introducing the binding).
 */
class FlowAssignment(
    override val id: Int,
    val node: Node,
    val antecedent: FlowNode,
) : FlowNode

/**
 * Flow node within a branch where a condition is known to be true or false.
 * Used for `if`/`while`/`do-while`/`for`/`?:` and short-circuit operators
 * (`&&`/`||`/`??`).
 */
class FlowCondition(
    override val id: Int,
    val isTrue: Boolean,
    val expression: Expression,
    val antecedent: FlowNode,
) : FlowNode

/**
 * Flow node within a switch case clause range where the switch expression is
 * known to equal one of the case values in [clauseStart, clauseEnd).
 */
class FlowSwitchClause(
    override val id: Int,
    val switchStatement: SwitchStatement,
    val clauseStart: Int,
    val clauseEnd: Int,
    val antecedent: FlowNode,
) : FlowNode

/**
 * Flow node after a call expression. Used by the checker for assertion
 * functions (e.g. `assert(x)` narrows `x` to non-null in code after the call).
 */
class FlowCall(
    override val id: Int,
    val node: CallExpression,
    val antecedent: FlowNode,
) : FlowNode

/**
 * Flow node after an array mutation (push/pop/shift/etc.). The checker may
 * widen the array's element type in code after the mutation.
 */
class FlowArrayMutation(
    override val id: Int,
    val node: Node,
    val antecedent: FlowNode,
) : FlowNode

/**
 * The control-flow graph for a single source file. Maps AST node keys
 * (pos|end packed Long, see [nodeKey]) to the [FlowNode] that represents the
 * flow just before that node is evaluated.
 *
 * Built by [FlowGraphBuilder] during [Binder.bind]. Stored on [BinderResult].
 */
class FlowGraph internal constructor(
    /**
     * (WARM.23) round 896 — a [LongKeyMap], not the `mutableMapOf<Long, FlowNode>`
     * this was until then. Round 894 § 9(3) audited it as get/put/`size` only and
     * the swap makes that audit PERMANENT rather than a claim about today's
     * source: [LongKeyMap] has no iterator, no `keys`, no `entries`, so the
     * rounds-754/776/778 iteration-order hazard — the one that is invisible in
     * every output diff — is now a compile error rather than a review item.
     *
     * Keys come from [flowKey], never [nodeKey]: see its KDoc for the sentinel.
     */
    internal val nodeToFlow: LongKeyMap<FlowNode>,
    /** B464: closure (Arrow/FunctionExpression) [FlowStart]s carrying [FlowStart.outerFlow]. */
    val closureStarts: List<FlowStart> = emptyList(),
    /** Round 426 (faithful TS2563): the file this graph was built from — lets the
     *  checker attribute a depth-tripped flow walk to its containing
     *  function-or-module block (tsc `reportFlowControlError`). */
    val sourceFile: SourceFile? = null,
    /** Round 426 (faithful TS2563): EVERY function-like body's [FlowStart] (superset
     *  of [closureStarts]) — the innermost `container` whose body block contains a
     *  reference position is tsc's `findAncestor(reference, isFunctionOrModuleBlock)`
     *  answer (the SourceFile when none contains it). */
    val containerStarts: List<FlowStart> = emptyList(),
    /**
     * (NARROW.2)(f) round 855: every NAME-CONSUMING flow node minted for this file —
     * the [FlowCondition] / [FlowAssignment] / [FlowCall] / [FlowSwitchClause] nodes
     * whose narrowers take the walked reference's `name` as an argument. Retained so
     * [narrowableRoots] can be derived on demand; `null` means no inventory was
     * supplied, which [narrowableRoots] reports as "unknown" rather than "empty".
     */
    private val narrowingNodes: List<FlowNode>? = null,
    /**
     * (WARM.11) round 864 — the nodes [FlowGraphBuilder.recordFlow] wrote, which
     * is all the nodeId side table below needs. `null` means "no list supplied",
     * and then the pre-864 whole-tree walk runs — the two fills are equivalent
     * (see the `init` block), so a caller that has no list loses nothing but
     * speed.
     */
    recordedNodes: List<Node>? = null,
) {
    // INV.2(b): nodeId-indexed fast path for [flowAt] — the pilot array-indexed side
    // table. Pre-computed here from the FINISHED map by walking the tree, so an
    // in-tree node's array answer is BY CONSTRUCTION exactly what the map returns
    // for its (pos,end) key — including the extent-ALIASING the Long key produces
    // (a wrapper and a same-extent child share one map entry; both their slots get
    // that shared answer). [nodeById] verifies ownership by IDENTITY: any node NOT
    // in this graph's tree (a synthesized copy, a foreign file's node) takes the
    // exact legacy map path, so the fast path is behavior-preserving by construction.
    private val flowById: Array<FlowNode?>
    private val nodeById: Array<Node?>

    // (ENGINE.2b) round 788: B464's innermost-enclosing-closure query, precomputed.
    // `emitTs18048ForClosureCapturedUndefinedReceiver` asks "which closure most
    // tightly contains this position" once per property access whose Identifier
    // receiver survives the round-489 pre-gate — 15,483 times on the compiler
    // profile, each a LINEAR scan of every closure in the file (8.9 us; 138 ms =
    // 46% of the walker, measured round 787). The question is a pure INTERVAL
    // query, so it is answered here from a pos-sorted array plus, per entry, the
    // index of its nearest ENCLOSING entry (one stack sweep). A query is a binary
    // search plus a walk bounded by the closure NESTING DEPTH.
    private val csPos: IntArray
    private val csEnd: IntArray
    /** Index of the nearest enclosing entry, or -1. */
    private val csOuter: IntArray
    private val csStart: Array<FlowStart?>

    init {
        val count = sourceFile?.nodeCount ?: 0
        flowById = arrayOfNulls(count)
        nodeById = arrayOfNulls(count)
        // (WARM.11) round 864 — this walk and the interval build below abut
        // across the whole constructor, so their two rows partition
        // [FrontEnd.FLOW_INDEX] and the residue is a partition check. The census
        // counts NODES, because the cost here is per node visited while the row
        // is closed per file: without it the row cannot be compared to anything
        // (round 758, in its converse direction).
        val tWalk = FrontEnd.t()
        var visited = 0L
        var answered = 0L
        // (WARM.11) round 864 — fill the side table from the RECORDED nodes.
        //
        // **Why this is exactly the whole-tree walk's answer, for every node.**
        // The table has ONE reader, [flowAt], which uses it only when
        // `nodeById[id] === node` and otherwise falls back to
        // `nodeToFlow.get(flowKey(node))`. Take any node of this file:
        //  * RECORDED — both fills put `nodeToFlow.get(flowKey(node))` in its slot
        //    (this one reads the FINISHED map, so a key written twice lands on
        //    the same final value the walk would have read);
        //  * in the tree but NOT recorded — the walk stored
        //    `nodeToFlow.get(flowKey(node))`, which is `null` unless some recorded
        //    node shares its `(pos,end)` extent; here the slot stays empty and
        //    `flowAt` performs that identical lookup itself;
        //  * not in this tree at all — neither fill touches it, and `flowAt`
        //    took the map path before and takes it now.
        // So the fills differ only in WHERE the map lookup happens for the
        // second class, and round 788's question — how much work that MOVES —
        // is answered by `FrontEnd`'s `flowAt` census, not by argument.
        if (recordedNodes != null && !FlowIndex.legacy) {
            for (k in recordedNodes.indices) {
                val node = recordedNodes[k]
                val id = (node as NodeBase).nodeId
                if (id in 0 until count) {
                    nodeById[id] = node
                    val f = nodeToFlow.get(flowKey(node))
                    flowById[id] = f
                    if (FrontEnd.mode == FrontEnd.ON) {
                        visited++
                        if (f != null) answered++
                    }
                }
            }
        } else if (sourceFile != null && count > 0) {
            val stack = ArrayList<Node>(64)
            val push: (Node) -> Unit = { stack.add(it) }
            stack.add(sourceFile)
            while (stack.isNotEmpty()) {
                val node = stack.removeAt(stack.size - 1)
                val id = (node as NodeBase).nodeId
                if (id in 0 until count) {
                    nodeById[id] = node
                    val f = nodeToFlow.get(flowKey(node))
                    flowById[id] = f
                    if (FrontEnd.mode == FrontEnd.ON) {
                        visited++
                        if (f != null) answered++
                    }
                }
                forEachChild(node, push)
            }
        }
        FrontEnd.close(FrontEnd.IDX_SIDETABLE, tWalk)
        FrontEnd.addFlowIndexCensus(visited, answered)
        val tClosures = FrontEnd.t()
        // Stable sort by container pos: entries sharing a pos keep their
        // `closureStarts` order, which is what the replaced scan's STRICT
        // `c.pos > bestPos` selected among them (see [innermostClosureAt]).
        val sorted = closureStarts.filter { it.container != null }.sortedBy { it.container!!.pos }
        val n = sorted.size
        csPos = IntArray(n)
        csEnd = IntArray(n)
        csOuter = IntArray(n)
        csStart = arrayOfNulls(n)
        val open = IntArray(n)
        var sp = 0
        for (i in 0 until n) {
            val c = sorted[i].container!!
            csPos[i] = c.pos
            csEnd[i] = c.end
            csStart[i] = sorted[i]
            while (sp > 0 && csEnd[open[sp - 1]] <= c.pos) sp--
            csOuter[i] = if (sp > 0) open[sp - 1] else -1
            open[sp++] = i
        }
        FrontEnd.close(FrontEnd.IDX_CLOSURES, tClosures)
    }

    // (NARROW.2)(f) round 855 — the narrowable-root set, computed at most once per
    // file and only if something asks. See [narrowableRoots].
    private var rootsComputed = false
    private var roots: Set<String>? = null

    /**
     * (NARROW.2)(f) round 855: the ROOT binding names that any narrowing node in
     * this file could possibly narrow — a conservative SUPERSET, `null` when this
     * graph was built without a narrowing-node inventory (then nothing may be
     * refused).
     *
     * **Why a name that is absent cannot narrow.** `narrowTypeFromFlow` reaches a
     * type different from the declared one only through four narrowers, and each
     * one takes the walked reference's dotted `name` and matches it against a
     * PATH occurring inside the flow node it belongs to:
     * `applyConditionNarrowing` (over [FlowCondition.expression], including its
     * aliased-condition arm, which resolves the alias to an initializer that is
     * itself a [FlowAssignment] in this same file), `narrowByAssignmentRhs` (over
     * [FlowAssignment.node]), `narrowByAssertCall` (over [FlowCall.node]'s
     * arguments) and `narrowBySwitchClause` (over the switch expression and its
     * case expressions). Every such path's ROOT is an `Identifier` — `this` is
     * parsed as `Identifier("this")` — so collecting every identifier text in
     * those subtrees over-approximates every name any of them can narrow.
     *
     * The set is per FILE, which also covers [FlowStart.outerFlow]: a closure
     * reading a narrow established in its enclosing scope walks into flow nodes
     * of the same file.
     *
     * **What it deliberately does NOT cover:** the two NAME-INDEPENDENT arms that
     * answer `never` — [FlowUnreachable] and an empty [FlowBranchLabel]. A caller
     * that must not miss those has to say so; `cmamNarrowedAnyReceiverType` may
     * refuse them because `never` is a `Type.Intrinsic`, which its next test
     * rejects anyway.
     */
    fun narrowableRoots(): Set<String>? {
        if (!rootsComputed) {
            rootsComputed = true
            val nodes = narrowingNodes
            if (nodes != null) {
                val out = HashSet<String>()
                for (fn in nodes) when (fn) {
                    is FlowCondition -> collectIdentifierTexts(fn.expression, out)
                    is FlowAssignment -> collectIdentifierTexts(fn.node, out)
                    is FlowCall -> collectIdentifierTexts(fn.node, out)
                    is FlowArrayMutation -> collectIdentifierTexts(fn.node, out)
                    is FlowSwitchClause -> {
                        collectIdentifierTexts(fn.switchStatement.expression, out)
                        for (clause in fn.switchStatement.caseBlock) {
                            if (clause is CaseClause) collectIdentifierTexts(clause.expression, out)
                        }
                    }
                    else -> {}
                }
                roots = out
            }
        }
        return roots
    }

    /** ITERATIVE by the checker-walker rule — a deep `a && b && …` condition chain
     *  must not recurse (see CLAUDE.md's binary-expression walker gotcha). */
    private fun collectIdentifierTexts(root: Node, out: MutableSet<String>) {
        val stack = ArrayList<Node>(16)
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            if (node is Identifier) out.add(node.text)
            forEachChild(node) { stack.add(it) }
        }
    }

    /** The flow just before [node] is evaluated — array-indexed for in-tree nodes,
     *  the legacy [nodeToFlow] lookup for everything else. */
    fun flowAt(node: Node): FlowNode? {
        val id = (node as NodeBase).nodeId
        if (id >= 0 && id < nodeById.size && nodeById[id] === node) {
            val f = flowById[id]
            // (WARM.11) round 864 — the round-788 census, taken BEFORE anything is
            // built: an in-tree node whose slot is NULL is a query that a side
            // table filled only from the RECORDED nodes would answer by the map
            // fallback instead. Its count is therefore exactly the work such a
            // rewrite would MOVE from build time to query time, and only a census
            // can say whether that is 0 or 600,000.
            if (FrontEnd.mode == FrontEnd.ON) FrontEnd.addFlowAt(if (f == null) 1 else 0)
            // (WARM.12) round 865 — the hand-out. Every walk in the checker starts
            // at a node this method returned, so hooking here means no flow node
            // can reach a consumer through an unhooked channel.
            if (FlowCensus.on && f != null) FlowCensus.touch(f, FlowCensus.CH_FLOWAT)
            return f
        }
        if (FrontEnd.mode == FrontEnd.ON) FrontEnd.addFlowAt(2)
        val fallback = nodeToFlow.get(flowKey(node))
        if (FlowCensus.on && fallback != null) FlowCensus.touch(fallback, FlowCensus.CH_FLOWAT)
        return fallback
    }

    /**
     * (ENGINE.2b) round 788: the [FlowStart] of the innermost closure whose
     * container range `[pos, end)` contains [pos] — `null` when no closure does.
     *
     * Exactly the answer of the linear scan it replaced: *among the containers
     * that contain [pos], the one with the greatest `container.pos`; ties broken
     * by the earliest position in [closureStarts]* (the scan kept its incumbent
     * on `c.pos > bestPos`, a strict comparison). Container ranges within one
     * file are nested-or-disjoint, so the greatest-`pos` container is the
     * innermost one, and [csOuter] walks straight up the enclosing chain.
     */
    fun innermostClosureAt(pos: Int): FlowStart? {
        val n = csPos.size
        if (n == 0) return null
        var lo = 0
        var hi = n - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (csPos[mid] <= pos) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        var i = idx
        while (i >= 0 && csEnd[i] <= pos) i = csOuter[i]
        if (i < 0) return null
        val tiePos = csPos[i]
        var j = i
        while (j > 0 && csPos[j - 1] == tiePos) {
            j--
            if (csEnd[j] > pos) i = j
        }
        return csStart[i]
    }
}

// ---------------------------------------------------------------------------
// FlowGraphBuilder — walks the AST and builds the flow graph
// ---------------------------------------------------------------------------

/**
 * Walks an AST and produces a [FlowGraph]. One instance per source file —
 * it is NOT thread-safe.
 *
 * Strategy:
 *   - Maintain a `currentFlow` mutable variable representing the flow at
 *     the walker's current position.
 *   - For each statement, update `currentFlow` to reflect the post-statement
 *     flow.
 *   - For each reference position (Identifier in expression context, etc.),
 *     record `currentFlow` in `nodeToFlow` BEFORE recursing into the
 *     reference's children (which may themselves change `currentFlow`).
 *   - For control-flow constructs (if/else, loops, try/catch, switch),
 *     create [FlowBranchLabel] / [FlowLoopLabel] join points and route
 *     branches through [FlowCondition] / [FlowSwitchClause] nodes.
 *   - For `return`/`throw`/`break`/`continue`, mark `currentFlow` as
 *     unreachable; `break`/`continue` route their pre-jump flow into the
 *     enclosing loop / switch / labeled-statement target.
 *
 * Each function-body container (FunctionDeclaration, FunctionExpression,
 * ArrowFunction, MethodDeclaration, Constructor, GetAccessor, SetAccessor,
 * ClassStaticBlockDeclaration) gets its own isolated subgraph with a fresh
 * [FlowStart].
 *
 * NOT YET CONSUMED by the checker — Phase 17 step 1 builds this
 * infrastructure with no behavior change. Step 2 will wire the graph into
 * narrowing for TS2454/TS2339/TS2774.
 */
/**
 * (FRONT.2) round 801 — the A/B and equivalence switch for the B464
 * reassignment scan, the one measured concentration inside `Binder.bind`.
 *
 * Both scanner implementations live in the binary so the two arms run on ONE
 * build and the boundary count is identical in both (round 793), and so the
 * pre-801 body can serve as an oracle rather than as a memory of what the code
 * used to say.
 *
 * [verify] is the discriminating instrument: it runs BOTH scanners on every
 * real scan of a real compile and counts divergences at entry granularity.
 * Its positive control is [bogus] — a deliberately broken fast path — because
 * round 790's cheaper "same comparison over the complement population" control
 * has no analogue for a pure refactor: there is no population where the two
 * implementations are *supposed* to differ, so a zero has to be shown to be a
 * live zero some other way.
 *
 * Production reads [legacy] once per scan (1,220 times per compile) and
 * nothing else; all three flags are off by default.
 */
/**
 * (WARM.11) round 864 — how the INV.2(b) side table inside [FlowGraph] is built.
 *
 * The pre-864 constructor walked the WHOLE tree (876,324 nodes on the compiler
 * profile) asking the `(pos,end)`-keyed map for each one, and 70% of those
 * questions had no answer. The side table is filled instead from the nodes
 * [FlowGraphBuilder.recordFlow] actually recorded (262,404), which is EXACTLY
 * equivalent — see [FlowGraph]'s constructor for the argument, and note that it
 * turns on `flowAt`'s own map fallback rather than on the two walks agreeing.
 *
 * [legacy] restores the whole-tree walk in the same binary: the A/B's other arm,
 * the differential pin's oracle, and the ablation's target.
 */
object FlowIndex {
    /** `--flowIndexLegacy` — build the side table by the pre-864 whole-tree walk. */
    var legacy: Boolean = false
}

object FlowScan {
    /** `--flowScanLegacy` — run the pre-801 scanner. The A/B's other arm. */
    var legacy: Boolean = false

    /** `--verifyFlowScan` — run BOTH and compare. Diagnostic only. */
    var verify: Boolean = false

    /** `--flowScanBogus` — positive control for [verify]: corrupt the fast path. */
    var bogus: Boolean = false

    /** `--flowEagerSet` — build the B464 suffix set eagerly (the pre-801 arm). */
    var eagerSet: Boolean = false

    /**
     * How many B464 suffix sets were CREATED versus ever actually QUERIED.
     * Always counted (two static increments per closure) — this ratio is the
     * whole justification for [SuffixNameSet] and must not need a probe flag
     * to be checkable.
     */
    var setsCreated: Long = 0
    var setsMaterialized: Long = 0

    /**
     * (WARM.27) round 900 — names actually INSERTED by [SuffixNameSet.materialize].
     *
     * [setsMaterialized] counts SETS and cannot decide round 899 § 33.8(5), whose
     * arithmetic is binary: a 21.6 ms JFR row on a 100%-insert owner is physically
     * real only at ~0.5-1.0 M `HashSet.add`s per rebuild. One accumulate per
     * materialisation (not per add), so this is as free as its two siblings and,
     * like them, always counted — a ratio that justifies a class must not need a
     * flag to be checkable.
     */
    var setEntries: Long = 0

    /**
     * (WARM.27) round 900 — the DENOMINATOR for [setEntries]: how many `ReassignScan`s
     * back those sets, and how many names they hold between them.
     *
     * Every [SuffixNameSet] is a SUFFIX of one cached scan's name array, so the
     * suffixes of one scan are NESTED and their union is the scan itself. That makes
     * `scanNames` the size of the ONE per-scan structure that could answer all of
     * them, and `scanNames` vs [setEntries] the whole price of the candidate.
     */
    var scansBuilt: Long = 0
    var scanNames: Long = 0

    /** (WARM.27) [SuffixNameIndex] builds actually performed, and names they inserted. */
    var indexesBuilt: Long = 0
    var indexEntries: Long = 0

    var scansCompared: Long = 0
    var scansDiverged: Long = 0
    var entriesCompared: Long = 0
    var entriesDiverged: Long = 0

    fun reset() {
        scansCompared = 0; scansDiverged = 0; entriesCompared = 0; entriesDiverged = 0
        setsCreated = 0; setsMaterialized = 0; setEntries = 0
        scansBuilt = 0; scanNames = 0
        indexesBuilt = 0; indexEntries = 0
    }

    internal fun compare(fastPositions: IntArray, fastNames: Array<String>,
                         slowPositions: IntArray, slowNames: Array<String>) {
        scansCompared++
        var diverged = false
        val n = maxOf(fastPositions.size, slowPositions.size)
        for (k in 0 until n) {
            entriesCompared++
            val fp = fastPositions.getOrNull(k); val sp = slowPositions.getOrNull(k)
            val fn = fastNames.getOrNull(k); val sn = slowNames.getOrNull(k)
            if (fp != sp || fn != sn) { entriesDiverged++; diverged = true }
        }
        if (diverged) scansDiverged++
    }

    fun report(): String =
        "== (FRONT.2) flow-scan equivalence ==\n" +
            "scans compared $scansCompared, diverged $scansDiverged; " +
            "entries compared $entriesCompared, diverged $entriesDiverged\n" +
            "suffix sets created $setsCreated, materialized $setsMaterialized, " +
            "names inserted $setEntries " +
            "(mean ${setEntries / maxOf(setsMaterialized, 1)} per materialised set)\n" +
            "reassign scans built $scansBuilt holding $scanNames names\n" +
            "suffix name indexes built $indexesBuilt inserting $indexEntries names\n"
}

/**
 * (FRONT.2) round 801 — a B464 reassigned-name set as a VIEW over the shared
 * scan's name array, materialised only if something ever asks it a question.
 *
 * The whole value is consumed at ONE place (`root in
 * flowNode.reassignedAfterNames`, Checker.kt), reached only from a narrowing
 * walk, and narrowing walks fell 75% between rounds 758 and 798 — so building
 * 2,014 hash sets averaging 135 entries during the BIND was paying for answers
 * almost nobody asks for. [FlowScan.setsCreated] and
 * [FlowScan.setsMaterialized] make that ratio a measurement rather than an
 * argument.
 *
 * `isEmpty` is answered from the bounds without materialising, because it is
 * the one question that can be decided arithmetically. Everything else
 * materialises, so the view is a strict `Set<String>` and not a partial one:
 * nothing downstream has to know it is lazy.
 */
internal class SuffixNameSet(
    private val index: SuffixNameIndex,
    private val lo: Int,
) : Set<String> {

    /** The stand-alone shape — one index of its own. Used by the equivalence pins. */
    constructor(names: Array<String>, lo: Int) : this(SuffixNameIndex(names), lo)

    private val names: Array<String> get() = index.names

    private var built: HashSet<String>? = null

    private fun materialize(): HashSet<String> {
        var b = built
        if (b == null) {
            b = HashSet(((names.size - lo) * 2).coerceAtLeast(8))
            for (k in lo until names.size) b.add(names[k])
            built = b
            FlowScan.setsMaterialized++
            FlowScan.setEntries += (names.size - lo).toLong()
        }
        return b
    }

    override val size: Int get() = materialize().size
    override fun isEmpty(): Boolean = lo >= names.size
    override fun iterator(): Iterator<String> = materialize().iterator()

    /**
     * (WARM.27) answered from the SHARED index, so the per-set hash set is never
     * built for the one question production asks.
     */
    override fun contains(element: String): Boolean = index.lastIndexOf(element) >= lo

    override fun containsAll(elements: Collection<String>): Boolean =
        elements.all { contains(it) }
}

/**
 * (WARM.27) round 900 — the LAST index at which each name occurs in one shared
 * reassignment scan, so that every [SuffixNameSet] over that scan answers
 * membership without building a hash set of its own.
 *
 * **Why one index answers all the suffixes.** A `SuffixNameSet(names, lo)` is
 * `{ names[k] : k >= lo }`, so the suffixes of one scan are NESTED and their union
 * is the scan. Membership is therefore a comparison, exactly:
 *
 *     e in suffix(lo)  <=>  (exists k >= lo) names[k] == e
 *                      <=>  max { k : names[k] == e } >= lo
 *
 * — which is why the index stores the LAST occurrence and not the first. Duplicate
 * names are the whole reason that distinction is load-bearing: with first-wins,
 * a name reassigned both before and after `lo` would answer `false`.
 *
 * **Why it is worth a class (round 899 § 33.8(5), measured round 900).** The 1,143
 * suffix sets a compiler-profile rebuild creates insert **767,521** names between
 * them, while the **1,220** scans backing them hold **15,331** names in total — a
 * 50x gap, because one cached scan backs hundreds of closures' suffixes. The JFR
 * row is 21.6 ms over 767,521 inserts = **28.1 ns each**, which is precisely a
 * `HashSet.add` with a cached `String` hash: this is the one candidate of round
 * 899's six whose profile figure survives round 898's plausibility test rather
 * than deflating ~3x under it.
 *
 * Built LAZILY, for the same reason [SuffixNameSet] itself is lazy: a scan whose
 * suffixes are never questioned pays nothing.
 */
internal class SuffixNameIndex(val names: Array<String>) {

    private var idx: HashMap<String, Int>? = null

    /** The greatest `k` with `names[k] == name`, or `-1` when the name is absent. */
    fun lastIndexOf(name: String): Int {
        var m = idx
        if (m == null) {
            m = HashMap((names.size * 2).coerceAtLeast(8))
            for (k in names.indices) m[names[k]] = k
            idx = m
            FlowScan.indexesBuilt++
            FlowScan.indexEntries += names.size.toLong()
        }
        return m[name] ?: -1
    }
}

class FlowGraphBuilder {

    private val nodeToFlow = LongKeyMap<FlowNode>(256)
    private var nextId = 0

    private var currentFlow: FlowNode = FlowStart(nextId++, null)

    /** Source text — used by B464 to scan for closure-captured-var reassignments. */
    private var sourceText: String = ""

    /**
     * Stack of enclosing function-like nodes (innermost last). Used by B464 to
     * find the enclosing function's source range when building a closure's
     * [FlowStart] (so we can detect assignments at/after the closure's position).
     */
    private val functionLikeStack: ArrayDeque<Node> = ArrayDeque()

    /** B464: closure [FlowStart]s collected during the walk (those with outerFlow). */
    private val closureStarts: MutableList<FlowStart> = mutableListOf()

    /** Round 426 (faithful TS2563): every function-like body's [FlowStart]. */
    private val containerStarts: MutableList<FlowStart> = mutableListOf()

    /**
     * Stack of break-target labels for unlabeled `break` statements.
     * Pushed when entering a loop or switch, popped when leaving.
     */
    private val breakTargetStack: ArrayDeque<FlowBranchLabel> = ArrayDeque()

    /**
     * Stack of continue-target labels for unlabeled `continue` statements.
     * Pushed when entering a loop, popped when leaving.
     */
    private val continueTargetStack: ArrayDeque<FlowLoopLabel> = ArrayDeque()

    /**
     * Map from label name to its (break, continue) target pair. Continue
     * target is null for non-loop labeled statements.
     */
    private val labeledTargets: MutableMap<String, Pair<FlowBranchLabel, FlowLoopLabel?>> = mutableMapOf()

    fun build(sourceFile: SourceFile): FlowGraph {
        sourceText = sourceFile.text
        reassignScanCache.clear() // per-file text — a reused builder must not serve stale scans
        narrowingNodes.clear() // ditto: a reused builder must not carry another file's nodes
        // (WARM.12) round 865 — open this file's mint inventory BEFORE the first
        // factory call, so no minted node can land in the previous file's.
        FlowCensus.beginFile(sourceFile.fileName, sourceFile.fileName.endsWith(".d.ts"))
        currentFlow = newStart(sourceFile)
        // (WARM.11) round 864 — the two spans below ABUT and cover everything
        // `build` does beyond three field writes, so [FrontEnd.FLOW_BIND] +
        // [FrontEnd.FLOW_INDEX] partitions [FrontEnd.BIND_FLOW] by construction
        // and the reported residue is a partition CHECK.
        val tMint = FrontEnd.t()
        bindEachStatement(sourceFile.statements)
        FrontEnd.close(FrontEnd.FLOW_BIND, tMint)
        // (FRONT.2) census — `nextId` IS the number of flow nodes minted for this
        // file; recorded so the BIND_FLOW row can be read per flow node instead
        // of per file. Behaviour-free when the probe is off.
        FrontEnd.addFlowCensus(nextId.toLong())
        FrontEnd.addFlowMintCensus(recordFlowCalls.toLong(), nodeToFlow.entryCount.toLong())
        val tIndex = FrontEnd.t()
        val graph = FlowGraph(
            nodeToFlow, closureStarts.toList(), sourceFile, containerStarts.toList(),
            if (PassTiming.detailed) narrowingNodes.toList() else null,
            recordedNodes,
        )
        FrontEnd.close(FrontEnd.FLOW_INDEX, tIndex)
        // (WARM.23) round 894 candidate (3): price the CONTAINER, not the owner.
        // The replay runs AFTER the graph is finished, over this file's real key
        // sequence, so it measures what a swap would recover rather than what the
        // present container costs. Off (`flowReplayReps == 0`) this is one static
        // read and a not-taken branch.
        if (MapCensus.flowReplayReps > 0) {
            val keys = LongArray(recordedNodes.size)
            for (k in recordedNodes.indices) keys[k] = flowKey(recordedNodes[k])
            MapCensus.replayFlowKeys(keys)
        }
        return graph
    }

    /** (WARM.11) census — how often [recordFlow] wrote, against how many DISTINCT
     *  `(pos,end)` keys survive in [nodeToFlow]. Probe-gated at the increment, so
     *  a production compile pays one static read and a not-taken branch per
     *  recorded node and retains nothing. */
    private var recordFlowCalls = 0

    /**
     * (WARM.11) round 864 — every node [recordFlow] wrote, in write order.
     *
     * This is what lets [FlowGraph] fill its nodeId side table without walking
     * the tree a second time. It is a LIST, not a set, and the duplicates are
     * deliberate: the fill re-reads [nodeToFlow] per entry, so a key written
     * twice lands on the same final value either way, and de-duplicating would
     * cost a hash per record to save nothing.
     */
    private val recordedNodes: ArrayList<Node> = ArrayList()

    /** (NARROW.2)(f) round 855: the name-consuming flow nodes minted for this file,
     *  accumulated at MINT time so the inventory is exhaustive by construction (a
     *  node reachable by no walk is merely a harmless surplus). One `add` per mint;
     *  the identifier collection itself is deferred to [FlowGraph.narrowableRoots].
     *
     *  PROBE-ONLY: filled and handed on only under `PassTiming.detailed`, so a
     *  production compile pays a not-taken branch per mint and retains nothing.
     *  Off the probe [FlowGraph.narrowableRoots] answers `null` = "unknown", which
     *  every caller must read as "refuse nothing". */
    private val narrowingNodes = ArrayList<FlowNode>()

    // ---- factories -------------------------------------------------------

    private fun newStart(container: Node?): FlowStart = noteMint(FlowStart(nextId++, container))
    private fun newUnreachable(): FlowUnreachable = noteMint(FlowUnreachable(nextId++))
    private fun newBranchLabel(): FlowBranchLabel = noteMint(FlowBranchLabel(nextId++))
    private fun newLoopLabel(): FlowLoopLabel = noteMint(FlowLoopLabel(nextId++))
    private fun newAssignment(node: Node, antecedent: FlowNode): FlowAssignment =
        noteMint(FlowAssignment(nextId++, node, antecedent)).also { noteNarrowingNode(it) }
    private fun newCondition(isTrue: Boolean, expr: Expression, antecedent: FlowNode): FlowCondition =
        noteMint(FlowCondition(nextId++, isTrue, expr, antecedent)).also { noteNarrowingNode(it) }
    private fun newSwitchClause(
        switchStmt: SwitchStatement,
        clauseStart: Int,
        clauseEnd: Int,
        antecedent: FlowNode,
    ): FlowSwitchClause =
        noteMint(FlowSwitchClause(nextId++, switchStmt, clauseStart, clauseEnd, antecedent))
            .also { noteNarrowingNode(it) }
    private fun newCall(call: CallExpression, antecedent: FlowNode): FlowCall =
        noteMint(FlowCall(nextId++, call, antecedent)).also { noteNarrowingNode(it) }

    /**
     * (WARM.12) round 865 — register one mint with [FlowCensus], attributed to the
     * enclosing function-like container (the LAZY-CONSTRUCTION axis).
     *
     * Every `nextId++` in this class flows through here EXCEPT the [currentFlow]
     * field initializer, which mints a placeholder `FlowStart` that `build`
     * immediately overwrites — so the census total is exactly
     * `FrontEnd.flowNodesBuilt - <graphs built>`, an identity
     * `FlowNodeCensusTest` pins so that a mint site added later without a
     * registration reddens instead of silently shrinking the denominator
     * (round 829).
     */
    private fun <T : FlowNode> noteMint(node: T): T {
        if (FlowCensus.on) {
            // A function's own `FlowStart` is minted BEFORE its container is
            // pushed, so it would otherwise be charged to the enclosing scope;
            // charge it to the function it starts, which is what makes
            // "container entirely unread" mean "this function's graph was never
            // consulted".
            val own = (node as? FlowStart)?.container?.pos
            FlowCensus.mint(node, own ?: functionLikeStack.lastOrNull()?.pos ?: -1)
        }
        return node
    }

    /** (WARM.12) round 865 — one AST node visited by the minting walk, charged to
     *  the container it is inside. The census's node counts say what was BUILT;
     *  only this says how much of the WALK a container is worth (round 758). */
    private fun noteVisit() {
        FlowCensus.visit(functionLikeStack.lastOrNull()?.pos ?: -1)
    }

    private fun noteNarrowingNode(node: FlowNode) {
        if (PassTiming.detailed) narrowingNodes.add(node)
    }

    // ---- helpers ---------------------------------------------------------

    private fun isReachable(): Boolean = currentFlow !is FlowUnreachable

    private fun setUnreachable() {
        currentFlow = newUnreachable()
    }

    private fun joinAntecedent(label: FlowBranchLabel, current: FlowNode) {
        if (current !is FlowUnreachable) {
            label.antecedents.add(current)
        }
    }

    private fun joinAntecedent(label: FlowLoopLabel, current: FlowNode) {
        if (current !is FlowUnreachable) {
            label.antecedents.add(current)
        }
    }

    /**
     * Resolve a [FlowBranchLabel] to a single flow node. If it has no
     * antecedents, the branch is unreachable. If it has one, return that
     * node directly. Otherwise, return the label as-is.
     */
    private fun finishBranchLabel(label: FlowBranchLabel): FlowNode = when {
        label.antecedents.isEmpty() -> newUnreachable()
        label.antecedents.size == 1 -> label.antecedents[0]
        else -> label
    }

    /** Record the flow node at a given AST node position. */
    private fun recordFlow(node: Node) {
        // Skip synthetic / sentinel nodes (pos == -1).
        if (node.pos < 0) return
        if (FrontEnd.mode == FrontEnd.ON) recordFlowCalls++
        recordedNodes.add(node)
        nodeToFlow.put(flowKey(node), currentFlow)
    }

    // ---- statement bindings ---------------------------------------------

    private fun bindEachStatement(statements: List<Statement>) {
        for (stmt in statements) {
            bindStatement(stmt)
        }
    }

    private fun bindStatement(stmt: Statement) {
        if (FlowCensus.on) noteVisit()
        when (stmt) {
            is Block -> bindEachStatement(stmt.statements)
            is VariableStatement -> bindVariableStatement(stmt)
            is ExpressionStatement -> bindExpression(stmt.expression)
            is IfStatement -> bindIfStatement(stmt)
            is DoStatement -> bindDoStatement(stmt)
            is WhileStatement -> bindWhileStatement(stmt)
            is ForStatement -> bindForStatement(stmt)
            is ForInStatement -> bindForInStatement(stmt)
            is ForOfStatement -> bindForOfStatement(stmt)
            is ReturnStatement -> bindReturnStatement(stmt)
            is ThrowStatement -> bindThrowStatement(stmt)
            is BreakStatement -> bindBreakStatement(stmt)
            is ContinueStatement -> bindContinueStatement(stmt)
            is SwitchStatement -> bindSwitchStatement(stmt)
            is TryStatement -> bindTryStatement(stmt)
            is LabeledStatement -> bindLabeledStatement(stmt)
            is WithStatement -> bindWithStatement(stmt)
            is FunctionDeclaration -> bindFunctionDeclaration(stmt)
            is ClassDeclaration -> bindClassDeclaration(stmt)
            is ModuleDeclaration -> bindModuleDeclaration(stmt)
            is ExportAssignment -> bindExpression(stmt.expression)
            // B1.3: record the current flow at the TypeAlias position so a
            // checker pass that wants to know "what's the flow context at
            // `type X = typeof a.b.c;`" can look it up via `nodeToFlow`.
            // TypeAlias bodies themselves don't change flow.
            is TypeAliasDeclaration -> recordFlow(stmt)
            // Type-only statements / no flow effect:
            is InterfaceDeclaration,
            is EnumDeclaration,
            is ImportDeclaration,
            is ImportEqualsDeclaration,
            is ExportDeclaration,
            is EmptyStatement,
            is DebuggerStatement,
            is NotEmittedStatement,
            is RawStatement -> { /* no flow change */ }
            else -> { /* fall-through; no-op */ }
        }
    }

    private fun bindVariableStatement(stmt: VariableStatement) {
        for (decl in stmt.declarationList.declarations) {
            // Walk the initializer first (its references see flow before assignment).
            decl.initializer?.let { bindExpression(it) }
            // The declaration introduces a binding — model as an assignment.
            if (isReachable()) {
                bindAssignmentTarget(decl.name, decl)
            }
        }
    }

    private fun bindIfStatement(stmt: IfStatement) {
        // Walk the condition first; references in it see the pre-if flow.
        bindCondition(stmt.expression)
        val preIf = currentFlow
        val postIf = newBranchLabel()

        // then-branch: condition assumed true
        currentFlow = newCondition(true, stmt.expression, preIf)
        bindStatement(stmt.thenStatement)
        joinAntecedent(postIf, currentFlow)

        // else-branch: condition assumed false (or skipped if no else)
        currentFlow = newCondition(false, stmt.expression, preIf)
        if (stmt.elseStatement != null) {
            bindStatement(stmt.elseStatement)
            joinAntecedent(postIf, currentFlow)
        } else {
            joinAntecedent(postIf, currentFlow)
        }

        currentFlow = finishBranchLabel(postIf)
    }

    private fun bindDoStatement(stmt: DoStatement) {
        val loopLabel = newLoopLabel()
        val postLoop = newBranchLabel()

        joinAntecedent(loopLabel, currentFlow)
        currentFlow = loopLabel

        breakTargetStack.addLast(postLoop)
        continueTargetStack.addLast(loopLabel)
        bindStatement(stmt.statement)
        continueTargetStack.removeLast()
        breakTargetStack.removeLast()

        // After the body, evaluate the condition.
        bindCondition(stmt.expression)
        val condFlow = currentFlow

        // True: back-edge to loop label.
        joinAntecedent(loopLabel, newCondition(true, stmt.expression, condFlow))
        // False: exit to postLoop.
        joinAntecedent(postLoop, newCondition(false, stmt.expression, condFlow))

        currentFlow = finishBranchLabel(postLoop)
    }

    private fun bindWhileStatement(stmt: WhileStatement) {
        val loopLabel = newLoopLabel()
        val postLoop = newBranchLabel()

        joinAntecedent(loopLabel, currentFlow)
        currentFlow = loopLabel

        // Condition is evaluated each iteration.
        bindCondition(stmt.expression)
        val condFlow = currentFlow

        // False: exit
        joinAntecedent(postLoop, newCondition(false, stmt.expression, condFlow))

        // True: enter body
        currentFlow = newCondition(true, stmt.expression, condFlow)

        breakTargetStack.addLast(postLoop)
        continueTargetStack.addLast(loopLabel)
        bindStatement(stmt.statement)
        continueTargetStack.removeLast()
        breakTargetStack.removeLast()

        // Back-edge from end-of-body to loop label
        joinAntecedent(loopLabel, currentFlow)

        currentFlow = finishBranchLabel(postLoop)
    }

    private fun bindForStatement(stmt: ForStatement) {
        // Initializer runs once before the loop.
        when (val init = stmt.initializer) {
            is VariableDeclarationList -> {
                for (decl in init.declarations) {
                    decl.initializer?.let { bindExpression(it) }
                    if (isReachable()) bindAssignmentTarget(decl.name, decl)
                }
            }
            is Expression -> bindExpression(init)
            else -> { /* no init */ }
        }

        val loopLabel = newLoopLabel()
        val postLoop = newBranchLabel()
        joinAntecedent(loopLabel, currentFlow)
        currentFlow = loopLabel

        val cond = stmt.condition
        if (cond != null) {
            bindCondition(cond)
            val condFlow = currentFlow
            joinAntecedent(postLoop, newCondition(false, cond, condFlow))
            currentFlow = newCondition(true, cond, condFlow)
        }

        breakTargetStack.addLast(postLoop)
        continueTargetStack.addLast(loopLabel)
        bindStatement(stmt.statement)
        continueTargetStack.removeLast()
        breakTargetStack.removeLast()

        stmt.incrementor?.let { bindExpression(it) }
        joinAntecedent(loopLabel, currentFlow)

        currentFlow = finishBranchLabel(postLoop)
    }

    private fun bindForInStatement(stmt: ForInStatement) {
        bindExpression(stmt.expression)

        val loopLabel = newLoopLabel()
        val postLoop = newBranchLabel()
        joinAntecedent(loopLabel, currentFlow)
        currentFlow = loopLabel
        // (CHK.69) The zero-iteration path leaves through the LOOP LABEL, not around
        // it — tsc's `bindForInOrForOfStatement` sets `currentFlow = preLoopLabel`
        // BEFORE `addAntecedent(postLoopLabel, currentFlow)`. Joining the PRE-loop flow
        // instead made the loop body unreachable BACKWARD from any read after the loop,
        // so `for (const n of xs) { h.req = 1 }` did not invalidate a narrow established
        // before it — a SHIPPED false negative for the `for-in`/`for-of` forms only
        // (`while` / `do` / `for(;;)` exit through their condition, which carries the
        // label). It also blinded [Checker.loopBodyMayAffectName]'s back-edge scan
        // whenever such a loop sat inside another one.
        joinAntecedent(postLoop, currentFlow)

        // B98.r124 (Blocker #1 substep): entering the for-in body implies the
        // iterated object is non-null/undefined (a nullish value yields no
        // iterations), so narrow the iterated expression to truthy within the body.
        // FP-safe by construction — a FlowCondition only ever SUPPRESSES diagnostics
        // (removes nullish constituents), never adds one. Scoped to the body: after
        // the loop `currentFlow` is the postLoop branch label whose antecedents are
        // the pre-loop flow + breaks, where the object keeps its declared nullish type.
        currentFlow = newCondition(isTrue = true, expr = stmt.expression, antecedent = currentFlow)

        // The initializer is assigned each iteration.
        when (val init = stmt.initializer) {
            is VariableDeclarationList -> {
                for (decl in init.declarations) {
                    if (isReachable()) bindAssignmentTarget(decl.name, decl)
                }
            }
            is Expression -> bindAssignmentTarget(init, init)
            else -> { /* */ }
        }

        breakTargetStack.addLast(postLoop)
        continueTargetStack.addLast(loopLabel)
        bindStatement(stmt.statement)
        continueTargetStack.removeLast()
        breakTargetStack.removeLast()

        joinAntecedent(loopLabel, currentFlow)

        currentFlow = finishBranchLabel(postLoop)
    }

    private fun bindForOfStatement(stmt: ForOfStatement) {
        bindExpression(stmt.expression)

        val loopLabel = newLoopLabel()
        val postLoop = newBranchLabel()
        joinAntecedent(loopLabel, currentFlow)
        currentFlow = loopLabel
        // (CHK.69) see [bindForInStatement] — the zero-iteration path leaves through
        // the LOOP LABEL, which is what makes the body reachable backward from a read
        // after the loop.
        joinAntecedent(postLoop, currentFlow)

        when (val init = stmt.initializer) {
            is VariableDeclarationList -> {
                for (decl in init.declarations) {
                    if (isReachable()) bindAssignmentTarget(decl.name, decl)
                }
            }
            is Expression -> bindAssignmentTarget(init, init)
            else -> { /* */ }
        }

        breakTargetStack.addLast(postLoop)
        continueTargetStack.addLast(loopLabel)
        bindStatement(stmt.statement)
        continueTargetStack.removeLast()
        breakTargetStack.removeLast()

        joinAntecedent(loopLabel, currentFlow)

        currentFlow = finishBranchLabel(postLoop)
    }

    private fun bindReturnStatement(stmt: ReturnStatement) {
        stmt.expression?.let { bindExpression(it) }
        setUnreachable()
    }

    private fun bindThrowStatement(stmt: ThrowStatement) {
        stmt.expression?.let { bindExpression(it) }
        setUnreachable()
    }

    private fun bindBreakStatement(stmt: BreakStatement) {
        val target = if (stmt.label != null) {
            labeledTargets[stmt.label.text]?.first
        } else {
            breakTargetStack.lastOrNull()
        }
        target?.let { joinAntecedent(it, currentFlow) }
        setUnreachable()
    }

    private fun bindContinueStatement(stmt: ContinueStatement) {
        val target = if (stmt.label != null) {
            labeledTargets[stmt.label.text]?.second
        } else {
            continueTargetStack.lastOrNull()
        }
        target?.let { joinAntecedent(it, currentFlow) }
        setUnreachable()
    }

    private fun bindSwitchStatement(stmt: SwitchStatement) {
        bindExpression(stmt.expression)
        val preSwitch = currentFlow
        val postSwitch = newBranchLabel()

        breakTargetStack.addLast(postSwitch)

        var hasDefault = false
        var fallthroughFlow: FlowNode? = null
        val clauses = stmt.caseBlock
        for ((i, clause) in clauses.withIndex()) {
            when (clause) {
                is CaseClause -> {
                    // A switch's case EXPRESSIONS are all evaluated at the switch head
                    // (before any body runs), so bind the expression at `preSwitch` —
                    // NOT at `currentFlow`, which after a prior clause body that ended in
                    // return/break is unreachable (`never`). Binding a discriminant read
                    // (`switch (true) { case x.kind === "b": }`) at that unreachable flow
                    // FP-emits TS2339-on-never on the case expression.
                    currentFlow = preSwitch
                    bindExpression(clause.expression)
                    // Entry flow into this clause: previous fallthrough OR
                    // a switch-clause flow predicated on the case expression
                    // matching.
                    val clauseEntry = newSwitchClause(stmt, i, i + 1, preSwitch)
                    val mergedEntry = if (fallthroughFlow != null) {
                        val merge = newBranchLabel()
                        joinAntecedent(merge, fallthroughFlow)
                        joinAntecedent(merge, clauseEntry)
                        finishBranchLabel(merge)
                    } else clauseEntry
                    currentFlow = mergedEntry
                    bindEachStatement(clause.statements)
                    fallthroughFlow = currentFlow
                }
                is DefaultClause -> {
                    hasDefault = true
                    val clauseEntry = newSwitchClause(stmt, i, i + 1, preSwitch)
                    val mergedEntry = if (fallthroughFlow != null) {
                        val merge = newBranchLabel()
                        joinAntecedent(merge, fallthroughFlow)
                        joinAntecedent(merge, clauseEntry)
                        finishBranchLabel(merge)
                    } else clauseEntry
                    currentFlow = mergedEntry
                    bindEachStatement(clause.statements)
                    fallthroughFlow = currentFlow
                }
                else -> { /* unexpected clause kind — skip */ }
            }
        }

        // End of last clause falls through to postSwitch.
        fallthroughFlow?.let { joinAntecedent(postSwitch, it) }

        // If no default clause, the switch may exit without matching anything — and
        // reaching postSwitch that way means EVERY case condition was false. Narrow the
        // no-match flow by each case expression being false (chained FlowConditions over
        // preSwitch) so post-switch narrowing reflects the un-matched scrutinee. For a
        // `switch (true) { case shape.kind === "circle": return … }` this leaves shape as
        // the non-circle members past the switch (tsc's post-switch exhaustiveness). For
        // a non-discriminant scrutinee each FlowCondition narrows nothing (harmless).
        // (REL.4) round 770: the FlowCondition chain above says "every case EXPRESSION is
        // falsy", which is the truth only for the `switch (true) { case <cond>: }` idiom —
        // for a discriminant switch the case expression is a VALUE (`SyntaxKind.A`), so the
        // chain narrows nothing and `Debug.assertNever(x)` after a `default`-less exhaustive
        // switch saw the whole declared type. tsc encodes the same edge as a switch-clause
        // flow with an EMPTY clause range (binder `createFlowSwitchClause(preSwitchCaseFlow,
        // node, 0, 0)`, read by `narrowTypeBySwitchOnDiscriminant`'s `clauseStart ===
        // clauseEnd` ⇒ treat as default). Layered ON TOP of the chain rather than replacing
        // it, so every existing `switch (true)` answer is untouched: [narrowBySwitchClause]
        // returns null for a range it cannot key and the caller keeps the antecedent.
        if (!hasDefault) {
            var noMatch = preSwitch
            for (clause in clauses) {
                if (clause is CaseClause) noMatch = newCondition(false, clause.expression, noMatch)
            }
            joinAntecedent(postSwitch, newSwitchClause(stmt, 0, 0, noMatch))
        }

        breakTargetStack.removeLast()
        currentFlow = finishBranchLabel(postSwitch)
    }

    private fun bindTryStatement(stmt: TryStatement) {
        // Conservative: any point inside try may throw, so the catch clause's
        // antecedent is the try-entry flow. Final flow after try-catch-finally
        // is the join of the try's normal completion + catch's normal completion.
        val preTry = currentFlow
        bindStatement(stmt.tryBlock)
        val tryEnd = currentFlow

        // Normal-completion join (try's normal end + catch's normal end): the flow
        // that continues AFTER the whole statement.
        val normalJoin = newBranchLabel()
        joinAntecedent(normalJoin, tryEnd)

        if (stmt.catchClause != null) {
            // Catch entry: pre-try flow (any throw point during try)
            currentFlow = preTry
            stmt.catchClause.variableDeclaration?.let { catchVar ->
                bindAssignmentTarget(catchVar.name, catchVar)
            }
            bindStatement(stmt.catchClause.block)
            joinAntecedent(normalJoin, currentFlow)
        }

        val normalCompletion = finishBranchLabel(normalJoin)

        if (stmt.finallyBlock != null) {
            // The finally block runs on EVERY exit path, including an EARLY throw
            // from the try/catch (before their normal completion). Its entry flow
            // therefore joins the pre-try flow (exceptional early exit) with the
            // normal completion. WITHOUT the pre-try antecedent, a try that always
            // returns/throws makes the normal completion unreachable, so every read
            // in finally washes to `never` → spurious TS2339 on cleanup code
            // (checker.ts checkGrammarRegularExpressionLiteral's scanner reset).
            val finallyEntry = newBranchLabel()
            joinAntecedent(finallyEntry, preTry)
            joinAntecedent(finallyEntry, normalCompletion)
            currentFlow = finishBranchLabel(finallyEntry)
            bindStatement(stmt.finallyBlock)
            // After the finally completes normally, control resumes at the try/catch's
            // normal completion — NOT the finally's exceptional-inclusive flow (which
            // would widen away the try/catch narrowing for the statements that follow).
            currentFlow = normalCompletion
        } else {
            currentFlow = normalCompletion
        }
    }

    private fun bindLabeledStatement(stmt: LabeledStatement) {
        // For labeled statements, register the label so labeled break/continue
        // can target the right place. For non-loop labeled statements, the
        // continue target is null.
        val name = stmt.label.text
        val breakLabel = newBranchLabel()
        val isLoop = stmt.statement is DoStatement
                || stmt.statement is WhileStatement
                || stmt.statement is ForStatement
                || stmt.statement is ForInStatement
                || stmt.statement is ForOfStatement
        val continueLabel = if (isLoop) newLoopLabel() else null
        val previous = labeledTargets[name]
        labeledTargets[name] = breakLabel to continueLabel
        // For loops, the labeled `continue X` should hit the loop's continue
        // label. Our loop builders create their own loop labels internally —
        // we'd need a richer mechanism to share. For step 1, accept that
        // labeled `continue` to a loop falls back to unreachable join (loss
        // of precision, no incorrect narrowing).
        bindStatement(stmt.statement)
        joinAntecedent(breakLabel, currentFlow)
        currentFlow = finishBranchLabel(breakLabel)
        if (previous != null) labeledTargets[name] = previous else labeledTargets.remove(name)
    }

    private fun bindWithStatement(stmt: WithStatement) {
        bindExpression(stmt.expression)
        bindStatement(stmt.statement)
    }

    private fun bindFunctionDeclaration(decl: FunctionDeclaration) {
        // Functions are assignments (hoisted) — but the body has its own flow.
        bindFunctionLikeBody(decl, decl.parameters, decl.body)
    }

    private fun bindClassDeclaration(decl: ClassDeclaration) {
        for (member in decl.members) {
            when (member) {
                is MethodDeclaration -> bindFunctionLikeBody(member, member.parameters, member.body)
                is Constructor -> bindFunctionLikeBody(member, member.parameters, member.body)
                is GetAccessor -> bindFunctionLikeBody(member, member.parameters, member.body)
                is SetAccessor -> bindFunctionLikeBody(member, member.parameters, member.body)
                is ClassStaticBlockDeclaration -> bindFunctionLikeBody(member, emptyList(), member.body)
                is PropertyDeclaration -> {
                    // Property initializers are evaluated in a fresh flow scope
                    // (the constructor's flow) — but for static properties,
                    // they're evaluated at class declaration time. Conservative:
                    // walk the initializer in the current flow.
                    member.initializer?.let { bindExpression(it) }
                }
                else -> { /* signatures, index sigs — no flow */ }
            }
        }
    }

    private fun bindModuleDeclaration(decl: ModuleDeclaration) {
        when (val body = decl.body) {
            is ModuleBlock -> bindEachStatement(body.statements)
            is ModuleDeclaration -> bindModuleDeclaration(body)
            else -> { /* */ }
        }
    }

    /**
     * Walk a function-like body in an isolated flow subgraph. Saves and
     * restores `currentFlow` and the break/continue/label stacks.
     */
    private fun bindFunctionLikeBody(container: Node, parameters: List<Parameter>, body: Node?) {
        val savedFlow = currentFlow
        val savedBreaks = breakTargetStack.toList()
        val savedContinues = continueTargetStack.toList()
        breakTargetStack.clear()
        continueTargetStack.clear()

        // B464: for a closure (ArrowFunction / FunctionExpression) nested inside
        // another function, capture the enclosing flow + the captured-var gate
        // info so the checker can flow narrowing into the closure body.
        val enclosing = functionLikeStack.lastOrNull()
        currentFlow =
            if ((container is ArrowFunction || container is FunctionExpression) && enclosing != null) {
                // (FRONT.2) round 801 — level 2 of the bind partition. Three
                // spans per CLOSURE (not per node), so the boundary count is
                // bounded by `closureStarts` and is reported beside them.
                val feR = FrontEnd.t()
                val reassigned = collectReassignedNamesInRange(
                    sourceText, container.pos, enclosing.end,
                )
                FrontEnd.close(FrontEnd.FLOW_REASSIGN, feR)
                val feL = FrontEnd.t()
                val locals = collectClosureLocalNames(parameters, body)
                FrontEnd.close(FrontEnd.FLOW_LOCALNAMES, feL)
                val feV = FrontEnd.t()
                val varDecls = collectEnclosingVarDecls(enclosing)
                FrontEnd.close(FrontEnd.FLOW_VARDECLS, feV)
                FrontEnd.addClosureCensus(reassigned)
                noteMint(
                    FlowStart(
                        id = nextId++,
                        container = container,
                        outerFlow = savedFlow,
                        reassignedAfterNames = reassigned,
                        localNames = locals,
                        enclosingVarDecls = varDecls,
                    )
                ).also { closureStarts.add(it) }
            } else {
                newStart(container)
            }
        // Round 426 (faithful TS2563): record every function-like body's start for
        // containing-container attribution (tsc findAncestor(isFunctionOrModuleBlock)).
        (currentFlow as? FlowStart)?.let { containerStarts.add(it) }

        functionLikeStack.addLast(container)

        // Parameters introduce bindings — model as assignments.
        for (param in parameters) {
            param.initializer?.let { bindExpression(it) }
            bindAssignmentTarget(param.name, param)
        }

        when (body) {
            is Block -> bindEachStatement(body.statements)
            is Expression -> bindExpression(body) // ArrowFunction with expression body
            null -> { /* overload signature, declare function — no body */ }
            else -> { /* shouldn't happen */ }
        }

        functionLikeStack.removeLast()
        currentFlow = savedFlow
        breakTargetStack.clear(); breakTargetStack.addAll(savedBreaks)
        continueTargetStack.clear(); continueTargetStack.addAll(savedContinues)
    }

    /**
     * B464: collect the names that are reassigned (`x = `, `x += `, `x++`, …)
     * within the source range [start, end). Used to compute the closure's
     * "captured var reassigned at/after the closure" set: a captured variable
     * reassigned at or after the closure's position is non-const, so its outer
     * narrowing must NOT flow into the closure body (matching tsc's
     * `isPastLastAssignment`). Pure text scan — false matches inside strings /
     * comments over-approximate toward "reassigned" (the conservative direction:
     * narrowing is withheld, so no extra narrowing is performed).
     */
    private fun collectReassignedNamesInRange(source: String, start: Int, end: Int): Set<String> {
        if (start < 0 || start >= source.length) return emptySet()
        val hi = minOf(end, source.length)
        if (hi <= start) return emptySet()
        // Perf (round 433): every closure inside one enclosing function queries the SAME
        // `hi` (= the enclosing function's end) with only `start` varying — and the
        // matcher's decisions at a position depend only on BACKWARD context (read via
        // getOrNull, unbounded) and `hi`, never on where the scan started (a scan
        // entering mid-word skips the partial word, exactly as a from-the-word's-start
        // scan attributes it to a position before the range). So one scan from the
        // lowest start seen serves all siblings via a position filter — exact semantics,
        // replacing the per-closure O(range) char scan that was ~14% of the tsc-source
        // self-compile (thousands of closures inside `createTypeChecker`-scale functions).
        val feS = FrontEnd.t()
        if (MapCensus.boxedKeyCensus) MapCensus.bk(MapCensus.BK_REASSIGN_SCAN, hi.toLong())
        var scan = reassignScanCache[hi]
        if (scan == null || scan.start > start) {
            scan = scanReassignedEntries(source, start, hi)
            if (MapCensus.boxedKeyCensus) MapCensus.bk(MapCensus.BK_REASSIGN_SCAN, hi.toLong())
            reassignScanCache[hi] = scan
            FrontEnd.addReassignScan((hi - start).toLong())
            FlowScan.scansBuilt++
            FlowScan.scanNames += scan.names.size.toLong()
        }
        FrontEnd.close(FrontEnd.FLOW_SCAN, feS)
        // First entry with position >= start (positions ascend); all entries are < hi.
        var lo = 0
        var h = scan.positions.size
        while (lo < h) {
            val mid = (lo + h) ushr 1
            if (scan.positions[mid] < start) lo = mid + 1 else h = mid
        }
        if (lo == scan.positions.size) return emptySet()
        // (FRONT.2) round 801 — the suffix is a VIEW, not a copy. The eager form
        // performed 273,226 `HashSet` insertions across 2,014 closures (135 per
        // closure, and a set that size resizes ~4 times from the default
        // capacity) to produce a value whose ONLY consumer is a single
        // `root in flowNode.reassignedAfterNames` membership test during
        // narrowing. [SuffixNameSet] builds the hash set on first query and
        // counts how many are ever queried, so the claim is measured rather
        // than assumed — the (IANY.1) shape, one phase earlier.
        val feB = FrontEnd.t()
        val result: Set<String> =
            if (FlowScan.eagerSet) {
                val eager = HashSet<String>()
                for (k in lo until scan.positions.size) eager.add(scan.names[k])
                FlowScan.setsMaterialized++
                eager
            } else {
                SuffixNameSet(scan.index, lo)
            }
        FlowScan.setsCreated++
        FrontEnd.close(FrontEnd.FLOW_SETBUILD, feB)
        return result
    }

    /** One reassignment-target scan over [start, hi): match positions (ascending) +
     *  the matched names, cached per `hi` in [reassignScanCache]. */
    private class ReassignScan(val start: Int, val positions: IntArray, val names: Array<String>) {
        /** (WARM.27) shared by every [SuffixNameSet] cut from this scan. */
        val index = SuffixNameIndex(names)
    }

    /**
     * (FRONT.2) unboxed neighbour read — the `Char?` of `getOrNull` boxes on the
     * JVM at every context probe. `' '` stands for "no such character"; see
     * [scanReassignedEntriesFast]'s comment for why that is equivalent to
     * `null` at every site that consumes it.
     */
    private fun charAtOr(source: String, len: Int, k: Int): Char =
        if (k >= 0 && k < len) source[k] else ' '

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    private fun isWordStartChar(c: Char): Boolean = c.isLetter() || c == '_' || c == '$'

    private val reassignScanCache = HashMap<Int, ReassignScan>()

    /**
     * (FRONT.2) round 801 — dispatch to the scanner under test. Both
     * implementations live in the binary so an A/B and an equivalence verifier
     * can run on ONE build (round 795's law 3, round 793's identical-boundary
     * rule: the span around this call is per CLOSURE and is unchanged either
     * way). [FlowScan.legacy] restores the pre-801 body verbatim.
     */
    private fun scanReassignedEntries(source: String, start: Int, hi: Int): ReassignScan {
        if (FlowScan.verify) {
            val fast = scanReassignedEntriesFast(source, start, hi)
            val slow = scanReassignedEntriesLegacy(source, start, hi)
            FlowScan.compare(fast.positions, fast.names, slow.positions, slow.names)
            return if (FlowScan.legacy) slow else fast
        }
        return if (FlowScan.legacy) scanReassignedEntriesLegacy(source, start, hi)
        else scanReassignedEntriesFast(source, start, hi)
    }

    /**
     * The pre-801 scanner, kept VERBATIM as the equivalence oracle and as the
     * A/B's other arm. Two things make it expensive, and the census measured
     * both: it allocates a `substring` for EVERY identifier occurrence in the
     * range while keeping only the assignment targets, and every forward and
     * backward context read goes through `getOrNull`, whose `Char?` return
     * boxes on the JVM. [scanReassignedEntriesFast] removes exactly those two
     * and changes nothing else — see its own comment for why the `' '`
     * sentinel is equivalent to `null` at every use site here.
     */
    private fun scanReassignedEntriesLegacy(source: String, start: Int, hi: Int): ReassignScan {
        val positions = mutableListOf<Int>()
        val names = mutableListOf<String>()
        fun isWordChar(c: Char?) = c != null && (c.isLetterOrDigit() || c == '_' || c == '$')
        fun isWordStart(c: Char) = c.isLetter() || c == '_' || c == '$'
        var i = start
        while (i < hi) {
            val c = source[i]
            if (isWordStart(c) && !isWordChar(source.getOrNull(i - 1)) && source.getOrNull(i - 1) != '.') {
                var j = i + 1
                while (j < hi && isWordChar(source.getOrNull(j))) j++
                val name = source.substring(i, j)
                // prefix ++/-- (e.g. `++x`)
                var b = i - 1
                while (b >= 0 && (source[b] == ' ' || source[b] == '\t')) b--
                val prefixInc = b >= 1 &&
                    ((source[b] == '+' && source[b - 1] == '+') || (source[b] == '-' && source[b - 1] == '-'))
                // suffix assignment operator / ++/--
                var p = j
                while (p < hi && (source[p] == ' ' || source[p] == '\t')) p++
                val c0 = source.getOrNull(p); val c1 = source.getOrNull(p + 1)
                val c2 = source.getOrNull(p + 2); val c3 = source.getOrNull(p + 3)
                val suffixAssigned = when {
                    (c0 == '+' && c1 == '+') || (c0 == '-' && c1 == '-') -> true
                    c0 == '=' && c1 != '=' && c1 != '>' -> true
                    c0 != null && c0 in "+-*/%^" && c1 == '=' -> true
                    (c0 == '&' || c0 == '|') && c1 == '=' -> true
                    (c0 == '&' && c1 == '&' || c0 == '|' && c1 == '|' || c0 == '?' && c1 == '?') && c2 == '=' -> true
                    c0 == '*' && c1 == '*' && c2 == '=' -> true
                    c0 == '<' && c1 == '<' && c2 == '=' -> true
                    c0 == '>' && c1 == '>' && (c2 == '=' || (c2 == '>' && c3 == '=')) -> true
                    else -> false
                }
                if (prefixInc || suffixAssigned) { positions.add(i); names.add(name) }
                i = j
            } else {
                i++
            }
        }
        return ReassignScan(start, positions.toIntArray(), names.toTypedArray())
    }

    /**
     * (FRONT.2) round 801 — the same scan with its two measured costs removed.
     * The VERDICT logic below is character-for-character the legacy one; only
     * these two things differ, and neither can change an answer.
     *
     * 1. **The `substring` moves below the guard.** `name` is read at exactly
     *    one place in the legacy body — inside `if (prefixInc ||
     *    suffixAssigned)` — so allocating it before the test allocated one
     *    String per IDENTIFIER OCCURRENCE in the range while keeping only the
     *    assignment targets. The census reports both populations, and the ratio
     *    is what this recovers.
     *
     * 2. **`getOrNull` is replaced by [charAtOr].** `CharSequence.getOrNull`
     *    returns `Char?`, which boxes on the JVM at every forward/backward
     *    context read. [charAtOr] returns a `' '` for out of range instead.
     *    That is EQUIVALENT here rather than merely close, because a space and
     *    an absent character are treated identically by every use site:
     *    `isWordChar(' ')` is false as `isWordChar(null)` was; `' ' != '.'` as
     *    `null != '.'`; `' ' !in "+-*&/%^=<>|?-"` so every operator arm that
     *    required a specific character still fails; and the one arm that reads
     *    a NEGATIVE condition — `c0 == '=' && c1 != '=' && c1 != '>'` — needed
     *    `c1` to be neither `'='` nor `'>'`, which `null` satisfied and `' '`
     *    satisfies. A space cannot be confused with a real neighbouring space
     *    either, because the space-skipping loops have already advanced past
     *    every space before these reads. `--verifyFlowScan` checks the whole
     *    claim against the legacy implementation on real input rather than
     *    resting on this argument.
     */
    private fun scanReassignedEntriesFast(source: String, start: Int, hi: Int): ReassignScan {
        val positions = mutableListOf<Int>()
        val names = mutableListOf<String>()
        val len = source.length
        var words = 0L
        var recorded = 0L
        var i = start
        while (i < hi) {
            val c = source[i]
            if (isWordStartChar(c) &&
                !isWordChar(charAtOr(source, len, i - 1)) &&
                charAtOr(source, len, i - 1) != '.'
            ) {
                var j = i + 1
                while (j < hi && isWordChar(source[j])) j++
                words++
                // prefix ++/-- (e.g. `++x`)
                var b = i - 1
                while (b >= 0 && (source[b] == ' ' || source[b] == '\t')) b--
                val prefixInc = b >= 1 &&
                    ((source[b] == '+' && source[b - 1] == '+') || (source[b] == '-' && source[b - 1] == '-'))
                // suffix assignment operator / ++/--
                var p = j
                while (p < hi && (source[p] == ' ' || source[p] == '\t')) p++
                val c0 = charAtOr(source, len, p); val c1 = charAtOr(source, len, p + 1)
                val c2 = charAtOr(source, len, p + 2); val c3 = charAtOr(source, len, p + 3)
                val suffixAssigned = when {
                    (c0 == '+' && c1 == '+') || (c0 == '-' && c1 == '-') -> true
                    c0 == '=' && c1 != '=' && c1 != '>' -> true
                    c0 in "+-*/%^" && c1 == '=' -> true
                    (c0 == '&' || c0 == '|') && c1 == '=' -> true
                    (c0 == '&' && c1 == '&' || c0 == '|' && c1 == '|' || c0 == '?' && c1 == '?') && c2 == '=' -> true
                    c0 == '*' && c1 == '*' && c2 == '=' -> true
                    c0 == '<' && c1 == '<' && c2 == '=' -> true
                    c0 == '>' && c1 == '>' && (c2 == '=' || (c2 == '>' && c3 == '=')) -> true
                    else -> false
                }
                // The substring is built ONLY for a recorded entry — change (1).
                if (prefixInc || suffixAssigned) {
                    // `--flowScanBogus`: the positive control for the verifier.
                    // Drops the `%=` form, which no other instrument here can
                    // see, so a live verifier MUST report it.
                    if (!(FlowScan.bogus && c0 == '%')) {
                        positions.add(i); names.add(source.substring(i, j)); recorded++
                    }
                }
                i = j
            } else {
                i++
            }
        }
        FrontEnd.addScanCensus(words, recorded)
        return ReassignScan(start, positions.toIntArray(), names.toTypedArray())
    }

    /** B464: collect a closure's own binding names (params + body-declared) so a
     *  same-named shadow does not inherit the enclosing scope's narrowing. */
    private fun collectClosureLocalNames(parameters: List<Parameter>, body: Node?): Set<String> {
        val names = mutableSetOf<String>()
        for (p in parameters) collectBindingNames(p.name, names)
        if (body is Block) collectBodyDeclaredNames(body.statements, names)
        return names
    }

    /** B467: `var`-declared (function-scoped, hoisted) names → declaration anywhere in the
     *  enclosing function body — NOT descending into nested function-likes (var is bounded
     *  by the enclosing function). Used to withhold closure narrowing for captured `var`s
     *  and to recover their declared annotation type. Only simple Identifier `var x: T`
     *  declarations are recorded (binding patterns don't carry a single annotation). */
    private fun collectEnclosingVarDecls(enclosing: Node): Map<String, VariableDeclaration> {
        val body: Block? = when (enclosing) {
            is FunctionDeclaration -> enclosing.body
            is FunctionExpression -> enclosing.body
            is MethodDeclaration -> enclosing.body
            is Constructor -> enclosing.body
            is GetAccessor -> enclosing.body
            is SetAccessor -> enclosing.body
            is ArrowFunction -> enclosing.body as? Block
            else -> null
        }
        if (body == null) return emptyMap()
        val decls = mutableMapOf<String, VariableDeclaration>()
        collectVarDeclsInStmts(body.statements, decls)
        return decls
    }

    private fun collectVarDeclsInStmts(statements: List<Statement>, into: MutableMap<String, VariableDeclaration>) {
        for (stmt in statements) collectVarDeclsInStmt(stmt, into)
    }

    private fun collectVarDeclsInStmt(stmt: Statement, into: MutableMap<String, VariableDeclaration>) {
        fun varList(l: VariableDeclarationList?) {
            if (l != null && l.flags == SyntaxKind.VarKeyword) for (d in l.declarations) {
                (d.name as? Identifier)?.let { if (it.text !in into) into[it.text] = d }
            }
        }
        when (stmt) {
            is VariableStatement -> varList(stmt.declarationList)
            is Block -> collectVarDeclsInStmts(stmt.statements, into)
            is IfStatement -> { collectVarDeclsInStmt(stmt.thenStatement, into); stmt.elseStatement?.let { collectVarDeclsInStmt(it, into) } }
            is ForStatement -> { varList(stmt.initializer as? VariableDeclarationList); collectVarDeclsInStmt(stmt.statement, into) }
            is ForInStatement -> { varList(stmt.initializer as? VariableDeclarationList); collectVarDeclsInStmt(stmt.statement, into) }
            is ForOfStatement -> { varList(stmt.initializer as? VariableDeclarationList); collectVarDeclsInStmt(stmt.statement, into) }
            is WhileStatement -> collectVarDeclsInStmt(stmt.statement, into)
            is DoStatement -> collectVarDeclsInStmt(stmt.statement, into)
            is TryStatement -> {
                collectVarDeclsInStmts(stmt.tryBlock.statements, into)
                stmt.catchClause?.let { collectVarDeclsInStmts(it.block.statements, into) }
                stmt.finallyBlock?.let { collectVarDeclsInStmts(it.statements, into) }
            }
            is SwitchStatement -> stmt.caseBlock.forEach { clause ->
                val cs = when (clause) {
                    is CaseClause -> clause.statements
                    is DefaultClause -> clause.statements
                    else -> emptyList()
                }
                collectVarDeclsInStmts(cs, into)
            }
            is LabeledStatement -> collectVarDeclsInStmt(stmt.statement, into)
            is WithStatement -> collectVarDeclsInStmt(stmt.statement, into)
            else -> {} // do NOT descend into FunctionDeclaration/ClassDeclaration (var scope boundary)
        }
    }

    private fun collectBindingNames(target: Node, into: MutableSet<String>) {
        when (target) {
            is Identifier -> into.add(target.text)
            is ObjectBindingPattern -> target.elements.forEach { collectBindingNames(it.name, into) }
            is ArrayBindingPattern -> target.elements.forEach {
                if (it is BindingElement) collectBindingNames(it.name, into)
            }
            else -> {}
        }
    }

    /** Collect var/let/const/function/class declaration names directly inside the
     *  given statement list, recursing into nested non-function statements only. */
    private fun collectBodyDeclaredNames(statements: List<Statement>, into: MutableSet<String>) {
        for (stmt in statements) collectStmtDeclaredNames(stmt, into)
    }

    private fun collectStmtDeclaredNames(stmt: Statement, into: MutableSet<String>) {
        when (stmt) {
            is VariableStatement -> stmt.declarationList.declarations.forEach { collectBindingNames(it.name, into) }
            is FunctionDeclaration -> stmt.name?.let { into.add(it.text) }
            is ClassDeclaration -> stmt.name?.let { into.add(it.text) }
            is Block -> collectBodyDeclaredNames(stmt.statements, into)
            is IfStatement -> {
                collectStmtDeclaredNames(stmt.thenStatement, into)
                stmt.elseStatement?.let { collectStmtDeclaredNames(it, into) }
            }
            is ForStatement -> {
                (stmt.initializer as? VariableDeclarationList)?.declarations?.forEach { collectBindingNames(it.name, into) }
                collectStmtDeclaredNames(stmt.statement, into)
            }
            is ForInStatement -> {
                (stmt.initializer as? VariableDeclarationList)?.declarations?.forEach { collectBindingNames(it.name, into) }
                collectStmtDeclaredNames(stmt.statement, into)
            }
            is ForOfStatement -> {
                (stmt.initializer as? VariableDeclarationList)?.declarations?.forEach { collectBindingNames(it.name, into) }
                collectStmtDeclaredNames(stmt.statement, into)
            }
            is WhileStatement -> collectStmtDeclaredNames(stmt.statement, into)
            is DoStatement -> collectStmtDeclaredNames(stmt.statement, into)
            is TryStatement -> {
                collectBodyDeclaredNames(stmt.tryBlock.statements, into)
                stmt.catchClause?.let { cc ->
                    (cc.variableDeclaration?.name)?.let { collectBindingNames(it, into) }
                    collectBodyDeclaredNames(cc.block.statements, into)
                }
                stmt.finallyBlock?.let { collectBodyDeclaredNames(it.statements, into) }
            }
            is SwitchStatement -> stmt.caseBlock.forEach { clause ->
                val clauseStmts = when (clause) {
                    is CaseClause -> clause.statements
                    is DefaultClause -> clause.statements
                    else -> emptyList()
                }
                collectBodyDeclaredNames(clauseStmts, into)
            }
            is LabeledStatement -> collectStmtDeclaredNames(stmt.statement, into)
            is WithStatement -> collectStmtDeclaredNames(stmt.statement, into)
            else -> {}
        }
    }

    /**
     * Bind an assignment target. For simple identifiers, creates a
     * [FlowAssignment]. For destructuring patterns, recurses into elements.
     * The `declarationNode` is what the FlowAssignment will reference (the
     * VariableDeclaration / Parameter / BindingElement / LHS expression).
     */
    private fun bindAssignmentTarget(target: Node, declarationNode: Node) {
        when (target) {
            is Identifier -> {
                if (isReachable()) {
                    currentFlow = newAssignment(declarationNode, currentFlow)
                }
            }
            // NOTE (round 386): property-path `=` targets are handled by the
            // PropertyAccessExpression arm FURTHER DOWN (bindExpression(target) — the
            // receiver-chain READ records — then newAssignment). A duplicate arm here
            // (without the bindExpression) shadowed it and silently dropped the LHS
            // read records: `this`-before-super and instanceof narrowing lost their
            // flow positions (narrowingOfDottedNames / checkSuperCallBeforeThisAccessing2
            // regressions). Kotlin `when` takes the FIRST matching arm — never add a
            // second arm for a kind that already has one below.
            // Destructuring default-value form `{a: x = 1}` or `[x = 1]` — the
            // PropertyAssignment.initializer / ArrayLiteralExpression element is
            // a BinaryExpression(=) where left is the actual target and right
            // is the default. Bind the default as a read, then recurse on the
            // target. Pure-pattern targets like `[a, b]` never reach this branch
            // (existing callers only pass BinaryExpression here when our
            // 17.46c destructuring recursion bottoms out on a default-value
            // shape inside a PropertyAssignment.initializer).
            is BinaryExpression -> if (target.operator == SyntaxKind.Equals) {
                bindExpression(target.right)
                // Round 460: keep threading the enclosing declarationNode (this arm is
                // only reached when the destructuring recursion bottoms out on a
                // default-value shape — see the comment above).
                bindAssignmentTarget(target.left, declarationNode)
            }
            is ObjectBindingPattern -> {
                for (element in target.elements) {
                    bindAssignmentTarget(element.name, element)
                }
            }
            is ArrayBindingPattern -> {
                for (element in target.elements) {
                    if (element is BindingElement) {
                        bindAssignmentTarget(element.name, element)
                    }
                }
            }
            is PropertyAccessExpression -> {
                bindExpression(target)
                if (isReachable()) {
                    currentFlow = newAssignment(declarationNode, currentFlow)
                }
            }
            is ElementAccessExpression -> {
                bindExpression(target)
                if (isReachable()) {
                    currentFlow = newAssignment(declarationNode, currentFlow)
                }
            }
            // 17.46c: array/object literal as assignment target. Used for destructuring
            // assignment (`[, x] = arr` / `({a, b} = obj)`) — distinct from
            // Array/ObjectBindingPattern which appear only in declaration positions.
            // Without this, the LHS falls into the else branch and no FlowAssignment
            // is registered for the binding, so the future top-level TS2454 walker
            // would FP-emit on subsequent reads.
            //
            // Round 460: each leaf recurses with the ENCLOSING [declarationNode]
            // (the whole `({a, b} = X)` BinaryExpression) so the narrowing walker can
            // see the destructuring RHS — `flowAssignmentTargetsName`'s
            // BinaryExpression arm walks pattern LHSes for the name via
            // `destructuringAssignTargetHasName`. (The pre-460 convention attached
            // the leaf Identifier, which carried no RHS and left destructuring
            // assignments narrowing-invisible: program.ts getReferencedFileLocation's
            // `({ pos, end } = file.referencedFiles[i])` never narrowed `pos` →
            // FP TS2322 at the final return.)
            is ArrayLiteralExpression -> {
                for (element in target.elements) {
                    when (element) {
                        is OmittedExpression -> { /* `[, x]` — skip elision */ }
                        is SpreadElement -> bindAssignmentTarget(element.expression, declarationNode)
                        is BinaryExpression -> if (element.operator == SyntaxKind.Equals) {
                            // `[a = 1, ...]` — default value reads (RHS), then `a` is the target
                            bindExpression(element.right)
                            bindAssignmentTarget(element.left, declarationNode)
                        } else {
                            bindAssignmentTarget(element, declarationNode)
                        }
                        else -> bindAssignmentTarget(element, declarationNode)
                    }
                }
            }
            is ObjectLiteralExpression -> {
                for (prop in target.properties) {
                    when (prop) {
                        is PropertyAssignment -> bindAssignmentTarget(prop.initializer, declarationNode)
                        is ShorthandPropertyAssignment -> {
                            prop.objectAssignmentInitializer?.let { bindExpression(it) }
                            bindAssignmentTarget(prop.name, declarationNode)
                        }
                        is SpreadAssignment -> bindAssignmentTarget(prop.expression, declarationNode)
                        else -> { /* computed names, methods, accessors — not assignment targets */ }
                    }
                }
            }
            else -> { /* computed / complex — skip */ }
        }
    }

    // ---- expression bindings --------------------------------------------

    /**
     * Walk an expression that's used in a condition position (if/while/for
     * cond, etc.). For step 1 this is the same as [bindExpression] — proper
     * predicate handling (short-circuit `&&`/`||`/`??`) lives inside
     * [bindExpression] for binary operators.
     */
    private fun bindCondition(expr: Expression) {
        bindExpression(expr)
    }

    private fun bindExpression(expr: Expression) {
        if (FlowCensus.on) noteVisit()
        when (expr) {
            is Identifier -> recordFlow(expr)
            is StringLiteralNode,
            is NumericLiteralNode,
            is BigIntLiteralNode,
            is RegularExpressionLiteralNode,
            is NoSubstitutionTemplateLiteralNode -> { /* literal — no flow change */ }
            is TemplateExpression -> {
                for (span in expr.templateSpans) {
                    bindExpression(span.expression)
                }
            }
            is ParenthesizedExpression -> bindExpression(expr.expression)
            is PropertyAccessExpression -> {
                bindExpression(expr.expression)
                recordFlow(expr)
            }
            is ElementAccessExpression -> {
                bindExpression(expr.expression)
                bindExpression(expr.argumentExpression)
                recordFlow(expr)
            }
            is CallExpression -> {
                bindExpression(expr.expression)
                for (arg in expr.arguments) bindExpression(arg)
                // Mark a FlowCall for potential assertion-function narrowing.
                if (isReachable()) {
                    currentFlow = newCall(expr, currentFlow)
                }
            }
            is NewExpression -> {
                bindExpression(expr.expression)
                expr.arguments?.let { for (arg in it) bindExpression(arg) }
            }
            is TaggedTemplateExpression -> {
                bindExpression(expr.tag)
                (expr.template as? Expression)?.let { bindExpression(it) }
            }
            is TypeAssertionExpression -> bindExpression(expr.expression)
            is AsExpression -> bindExpression(expr.expression)
            is SatisfiesExpression -> bindExpression(expr.expression)
            is NonNullExpression -> bindExpression(expr.expression)
            is FunctionExpression -> bindFunctionLikeBody(expr, expr.parameters, expr.body)
            is ArrowFunction -> bindFunctionLikeBody(expr, expr.parameters, expr.body)
            is ClassExpression -> {
                // Class expression body: bind methods like ClassDeclaration.
                for (member in expr.members) {
                    when (member) {
                        is MethodDeclaration -> bindFunctionLikeBody(member, member.parameters, member.body)
                        is Constructor -> bindFunctionLikeBody(member, member.parameters, member.body)
                        is GetAccessor -> bindFunctionLikeBody(member, member.parameters, member.body)
                        is SetAccessor -> bindFunctionLikeBody(member, member.parameters, member.body)
                        is ClassStaticBlockDeclaration -> bindFunctionLikeBody(member, emptyList(), member.body)
                        is PropertyDeclaration -> member.initializer?.let { bindExpression(it) }
                        else -> { /* */ }
                    }
                }
            }
            is DeleteExpression -> bindExpression(expr.expression)
            is TypeOfExpression -> bindExpression(expr.expression)
            is VoidExpression -> bindExpression(expr.expression)
            is AwaitExpression -> bindExpression(expr.expression)
            is YieldExpression -> expr.expression?.let { bindExpression(it) }
            is PrefixUnaryExpression -> {
                bindExpression(expr.operand)
                // ++/--/! at the prefix position — for ++ and --, the operand
                // is reassigned. Conservative: model as assignment if the
                // operand is a simple identifier.
                if (expr.operator == SyntaxKind.PlusPlus || expr.operator == SyntaxKind.MinusMinus) {
                    if (expr.operand is Identifier && isReachable()) {
                        currentFlow = newAssignment(expr.operand, currentFlow)
                    }
                }
            }
            is PostfixUnaryExpression -> {
                bindExpression(expr.operand)
                if (expr.operand is Identifier && isReachable()) {
                    currentFlow = newAssignment(expr.operand, currentFlow)
                }
            }
            is BinaryExpression -> bindBinaryExpression(expr)
            is ConditionalExpression -> bindConditionalExpression(expr)
            is ArrayLiteralExpression -> {
                for (el in expr.elements) bindExpression(el)
            }
            is ObjectLiteralExpression -> {
                for (prop in expr.properties) {
                    when (prop) {
                        is PropertyAssignment -> bindExpression(prop.initializer)
                        is ShorthandPropertyAssignment -> {
                            recordFlow(prop.name)
                            prop.objectAssignmentInitializer?.let { bindExpression(it) }
                        }
                        is SpreadAssignment -> bindExpression(prop.expression)
                        is MethodDeclaration -> bindFunctionLikeBody(prop, prop.parameters, prop.body)
                        is GetAccessor -> bindFunctionLikeBody(prop, prop.parameters, prop.body)
                        is SetAccessor -> bindFunctionLikeBody(prop, prop.parameters, prop.body)
                        else -> { /* computed names etc. */ }
                    }
                }
            }
            is SpreadElement -> bindExpression(expr.expression)
            is CommaListExpression -> {
                for (e in expr.elements) bindExpression(e)
            }
            is OmittedExpression -> { /* */ }
            is MetaProperty -> { /* new.target / import.meta — no flow */ }
            else -> { /* unhandled — leave currentFlow unchanged */ }
        }
    }

    private fun bindBinaryExpression(expr: BinaryExpression) {
        // Iteratively flatten the left-spine for "no flow change" operators
        // (arithmetic / comparison / bitwise / instanceof / in / comma) to avoid
        // StackOverflow on deeply nested left-associative chains like
        // `0 + 1 + 2 + ... + 1499`. Same semantic effect as recursing through
        // bindExpression(left) + bindExpression(right). Stops at any operator
        // that affects flow (&&, ||, ??, =, +=, etc.) and falls into the
        // recursive handler below for that node.
        if (isSimpleBinaryOp(expr.operator) && expr.left is BinaryExpression) {
            val rightStack = ArrayDeque<Expression>()
            var node: Expression = expr
            while (node is BinaryExpression && isSimpleBinaryOp(node.operator)) {
                rightStack.addLast(node.right)
                node = node.left
            }
            bindExpression(node)
            while (rightStack.isNotEmpty()) bindExpression(rightStack.removeLast())
            return
        }
        when (expr.operator) {
            // Short-circuit operators: && / || / ??
            SyntaxKind.AmpersandAmpersand -> {
                bindCondition(expr.left)
                val preRight = currentFlow
                val postExpr = newBranchLabel()
                joinAntecedent(postExpr, newCondition(false, expr.left, preRight))
                currentFlow = newCondition(true, expr.left, preRight)
                bindExpression(expr.right)
                joinAntecedent(postExpr, currentFlow)
                currentFlow = finishBranchLabel(postExpr)
            }
            SyntaxKind.BarBar -> {
                bindCondition(expr.left)
                val preRight = currentFlow
                val postExpr = newBranchLabel()
                joinAntecedent(postExpr, newCondition(true, expr.left, preRight))
                currentFlow = newCondition(false, expr.left, preRight)
                bindExpression(expr.right)
                joinAntecedent(postExpr, currentFlow)
                currentFlow = finishBranchLabel(postExpr)
            }
            SyntaxKind.QuestionQuestion -> {
                bindExpression(expr.left)
                val preRight = currentFlow
                val postExpr = newBranchLabel()
                joinAntecedent(postExpr, preRight)
                bindExpression(expr.right)
                joinAntecedent(postExpr, currentFlow)
                currentFlow = finishBranchLabel(postExpr)
            }
            // Assignment-flavored operators
            SyntaxKind.Equals -> {
                bindExpression(expr.right)
                bindAssignmentTarget(expr.left, expr)
            }
            SyntaxKind.PlusEquals,
            SyntaxKind.MinusEquals,
            SyntaxKind.AsteriskEquals,
            SyntaxKind.AsteriskAsteriskEquals,
            SyntaxKind.SlashEquals,
            SyntaxKind.PercentEquals,
            SyntaxKind.LessThanLessThanEquals,
            SyntaxKind.GreaterThanGreaterThanEquals,
            SyntaxKind.GreaterThanGreaterThanGreaterThanEquals,
            SyntaxKind.AmpersandEquals,
            SyntaxKind.BarEquals,
            SyntaxKind.CaretEquals,
            SyntaxKind.AmpersandAmpersandEquals,
            SyntaxKind.BarBarEquals,
            SyntaxKind.QuestionQuestionEquals -> {
                bindExpression(expr.left)
                bindExpression(expr.right)
                // M1.4-prep: compound assignments to PROPERTY paths are flow events too
                // (`result.cache ??= new Map()` — the ??=/||= post-state narrowing in
                // narrowByAssignmentRhs needs the node). Identifier LHS was already bound.
                if ((expr.left is Identifier || expr.left is PropertyAccessExpression) && isReachable()) {
                    currentFlow = newAssignment(expr, currentFlow)
                }
            }
            // Comma: left first then right; result type is right.
            SyntaxKind.Comma -> {
                bindExpression(expr.left)
                bindExpression(expr.right)
            }
            // All other binary operators: left then right; no flow change.
            else -> {
                bindExpression(expr.left)
                bindExpression(expr.right)
            }
        }
    }

    /** True for binary operators with no flow-graph effect — safe to flatten
     *  the left-spine iteratively in [bindBinaryExpression]. Excludes
     *  short-circuit ops (&&, ||, ??), Equals, compound assignments, and the
     *  short-circuit-assignment forms (&&=, ||=, ??=). Comma is included since
     *  it has no flow effect (just left-then-right binding). */
    private fun isSimpleBinaryOp(op: SyntaxKind): Boolean = when (op) {
        SyntaxKind.AmpersandAmpersand,
        SyntaxKind.BarBar,
        SyntaxKind.QuestionQuestion,
        SyntaxKind.Equals,
        SyntaxKind.PlusEquals,
        SyntaxKind.MinusEquals,
        SyntaxKind.AsteriskEquals,
        SyntaxKind.AsteriskAsteriskEquals,
        SyntaxKind.SlashEquals,
        SyntaxKind.PercentEquals,
        SyntaxKind.LessThanLessThanEquals,
        SyntaxKind.GreaterThanGreaterThanEquals,
        SyntaxKind.GreaterThanGreaterThanGreaterThanEquals,
        SyntaxKind.AmpersandEquals,
        SyntaxKind.BarEquals,
        SyntaxKind.CaretEquals,
        SyntaxKind.AmpersandAmpersandEquals,
        SyntaxKind.BarBarEquals,
        SyntaxKind.QuestionQuestionEquals -> false
        else -> true
    }

    private fun bindConditionalExpression(expr: ConditionalExpression) {
        bindCondition(expr.condition)
        val preBranch = currentFlow
        val postExpr = newBranchLabel()

        currentFlow = newCondition(true, expr.condition, preBranch)
        bindExpression(expr.whenTrue)
        joinAntecedent(postExpr, currentFlow)

        currentFlow = newCondition(false, expr.condition, preBranch)
        bindExpression(expr.whenFalse)
        joinAntecedent(postExpr, currentFlow)

        currentFlow = finishBranchLabel(postExpr)
    }
}
