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

    /** Depth-0 flow-narrowing walks launched (`flowWalkWithTripCheck` entries). */
    var narrowWalks: Long = 0

    /** Narrowing walks attributed to the launching pass. */
    val narrowWalksByPass = HashMap<String, Long>()

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
        getTypeOfExpressionDistinct.clear()
        getTypeOfExpressionByPass.clear()
        typeNodeCacheable = 0
        typeNodeCacheHits = 0
        typeNodeBypassed = 0
        narrowWalks = 0
        narrowWalksByPass.clear()
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
        appendLine("== counters ==")
        val distinct = getTypeOfExpressionDistinct.size.toLong()
        val factor = if (distinct > 0) (getTypeOfExpressionCalls * 10 / distinct) else 0L
        appendLine(
            "getTypeOfExpression: $getTypeOfExpressionCalls calls, ~$distinct distinct nodes " +
                "(recompute ~x${factor / 10}.${factor % 10}; distinct is pos-keyed, " +
                "cross-file collisions undercount it)"
        )
        val outsideExpr = getTypeOfExpressionByPass[OUTSIDE_PASS] ?: 0L
        appendLine("getTypeOfExpression outside init dispatch: $outsideExpr calls")
        val typeNodeTotal = typeNodeCacheable + typeNodeBypassed
        val bypassPct = if (typeNodeTotal > 0) typeNodeBypassed * 1000 / typeNodeTotal else 0L
        appendLine(
            "getTypeFromTypeNode: cacheable $typeNodeCacheable (hits $typeNodeCacheHits, " +
                "misses ${typeNodeCacheable - typeNodeCacheHits}) vs bypassed $typeNodeBypassed " +
                "(${bypassPct / 10}.${bypassPct % 10}% of resolutions bypass the cache)"
        )
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
    if (!PassTiming.enabled) {
        body()
        return
    }
    val saved = PassTiming.currentPass
    PassTiming.currentPass = name
    val mark = TimeSource.Monotonic.markNow()
    try {
        body()
    } finally {
        PassTiming.notePass(name, mark.elapsedNow().inWholeNanoseconds)
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
