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
 * (DISPATCH.1) step (a) — the opt-in derivation harness for the per-kind spine
 * handler table.
 *
 * ## Why
 *
 * `spineEnterNode` consults 46 handler entry points and `spineLeaveNode` 13,
 * for every one of the ~857k nodes of a compiler-profile run (round 716:
 * 14.8 µs/node enter+leave, of which only ~5.9 µs is type-system work). Most
 * handlers apply to a handful of node kinds. The fix is to dispatch only the
 * handlers that can fire for the node's [NodeKind]; the load-bearing part is
 * that the per-kind handler set must be DERIVED, not guessed.
 *
 * ## What this object is
 *
 * Three things, all **opt-in and behaviour-free when [mode] is [OFF]** (the
 * production `spineEnterNode`/`spineLeaveNode` branch on `mode != OFF` once and
 * otherwise run their untouched straight-line prologues):
 *
 * 1. **[PROBE]** — runs every handler through the by-id dispatcher, recording
 *    per (handler, node-kind) consult counts, per (handler, node-kind) OBSERVED
 *    WORK (handlers that cannot be closed by their own kind call [work] at each
 *    of their work points), and per (handler, node-kind) nanos. Single-threaded
 *    by construction: it runs on the check spine, never on the crawl's parse
 *    threads — which is why it, and not `PassTiming.nodeKindHistogram`, is the
 *    sound source for the table (round 717: the parse-time census is racy and
 *    always low by ~0.3%, fatal for a "can this handler fire here" question).
 * 2. **[GATED]** — runs ONLY `enterTable[kindId]` / `leaveTable[kindId]`. The
 *    corpus suite and the profile `--listAll` outputs must stay byte-identical;
 *    that is the empirical proof of the table, without landing any production
 *    dispatch change.
 * 3. The **table itself** ([enterClosure]/[leaveClosure]) — per handler either
 *    the closed set of node kinds it can act on, or `null` = OPEN (stays in the
 *    always-run list). See `docs/perf/dispatch-table.md` for the derivation and
 *    the per-handler justification.
 *
 * ## Soundness rule
 *
 * A closure is a claim that the handler does nothing observable for every kind
 * outside it. Only two justifications are accepted: (a) the handler's body is
 * one top-level `when ((node as NodeBase).kindId)` / `if (kindId != K) return`
 * over the listed kinds — a syntactic fact, machine-checkable; or (b) an
 * `is Statement` gate, whose kind set is [STATEMENT_KINDS]. Anything else —
 * parent-keyed edges, nodeId registries, frame-owner identity — is OPEN.
 * An empirically-observed work set is NEVER promoted to a closure: the corpus
 * is large but not a proof.
 */
object SpineDispatch {

    const val OFF = 0
    const val PROBE = 1
    const val GATED = 2

    /** Opt-in; [OFF] in production. Set by `--dispatchProbe`/`--dispatchGated`. */
    var mode: Int = OFF

    /** Dense [NodeKind] id count (ids are 0..KINDS-1). */
    const val KINDS = 138

    /** Dense [NodeKind] id -> const name, for the probe artifact only. */
    val kindNames: Array<String> = arrayOf(
        "SOURCE_FILE", "BLOCK", "EMPTY_STATEMENT", "VARIABLE_STATEMENT", "EXPRESSION_STATEMENT",
        "IF_STATEMENT", "DO_STATEMENT", "WHILE_STATEMENT", "FOR_STATEMENT", "FOR_IN_STATEMENT",
        "FOR_OF_STATEMENT", "CONTINUE_STATEMENT", "BREAK_STATEMENT", "RETURN_STATEMENT",
        "WITH_STATEMENT", "SWITCH_STATEMENT", "LABELED_STATEMENT", "THROW_STATEMENT",
        "TRY_STATEMENT", "DEBUGGER_STATEMENT", "NOT_EMITTED_STATEMENT", "RAW_STATEMENT",
        "FUNCTION_DECLARATION", "CLASS_DECLARATION", "INTERFACE_DECLARATION",
        "TYPE_ALIAS_DECLARATION", "ENUM_DECLARATION", "MODULE_DECLARATION", "IMPORT_DECLARATION",
        "IMPORT_EQUALS_DECLARATION", "EXPORT_DECLARATION", "EXPORT_ASSIGNMENT",
        "VARIABLE_DECLARATION", "VARIABLE_DECLARATION_LIST", "IDENTIFIER", "STRING_LITERAL_NODE",
        "NUMERIC_LITERAL_NODE", "BIG_INT_LITERAL_NODE", "REGULAR_EXPRESSION_LITERAL_NODE",
        "NO_SUBSTITUTION_TEMPLATE_LITERAL_NODE", "TEMPLATE_EXPRESSION", "TEMPLATE_SPAN",
        "ARRAY_LITERAL_EXPRESSION", "OBJECT_LITERAL_EXPRESSION", "PROPERTY_ACCESS_EXPRESSION",
        "ELEMENT_ACCESS_EXPRESSION", "CALL_EXPRESSION", "NEW_EXPRESSION",
        "TAGGED_TEMPLATE_EXPRESSION", "TYPE_ASSERTION_EXPRESSION", "PARENTHESIZED_EXPRESSION",
        "FUNCTION_EXPRESSION", "ARROW_FUNCTION", "DELETE_EXPRESSION", "TYPE_OF_EXPRESSION",
        "VOID_EXPRESSION", "AWAIT_EXPRESSION", "PREFIX_UNARY_EXPRESSION",
        "POSTFIX_UNARY_EXPRESSION", "BINARY_EXPRESSION", "CONDITIONAL_EXPRESSION",
        "YIELD_EXPRESSION", "SPREAD_ELEMENT", "CLASS_EXPRESSION", "AS_EXPRESSION",
        "NON_NULL_EXPRESSION", "SATISFIES_EXPRESSION", "META_PROPERTY", "OMITTED_EXPRESSION",
        "COMMA_LIST_EXPRESSION", "PROPERTY_DECLARATION", "METHOD_DECLARATION", "CONSTRUCTOR",
        "GET_ACCESSOR", "SET_ACCESSOR", "INDEX_SIGNATURE", "SEMICOLON_CLASS_ELEMENT",
        "CLASS_STATIC_BLOCK_DECLARATION", "TYPE_REFERENCE", "FUNCTION_TYPE", "CONSTRUCTOR_TYPE",
        "TYPE_QUERY", "TYPE_LITERAL", "ARRAY_TYPE", "TUPLE_TYPE", "UNION_TYPE",
        "INTERSECTION_TYPE", "CONDITIONAL_TYPE", "INDEXED_ACCESS_TYPE", "MAPPED_TYPE",
        "LITERAL_TYPE", "TEMPLATE_LITERAL_TYPE", "TEMPLATE_LITERAL_TYPE_SPAN",
        "PARENTHESIZED_TYPE", "TYPE_PREDICATE", "TYPE_OPERATOR", "REST_TYPE",
        "NAMED_TUPLE_MEMBER", "OPTIONAL_TYPE", "IMPORT_TYPE", "THIS_TYPE", "INFER_TYPE",
        "KEYWORD_TYPE_NODE", "PARAMETER", "DECORATOR", "HERITAGE_CLAUSE",
        "EXPRESSION_WITH_TYPE_ARGUMENTS", "ENUM_MEMBER", "TYPE_PARAMETER", "QUALIFIED_NAME",
        "PROPERTY_ASSIGNMENT", "SHORTHAND_PROPERTY_ASSIGNMENT", "SPREAD_ASSIGNMENT",
        "COMPUTED_PROPERTY_NAME", "OBJECT_BINDING_PATTERN", "ARRAY_BINDING_PATTERN",
        "BINDING_ELEMENT", "CASE_CLAUSE", "DEFAULT_CLAUSE", "CATCH_CLAUSE", "MODULE_BLOCK",
        "NAMESPACE_IMPORT", "NAMED_IMPORTS", "IMPORT_SPECIFIER", "NAMESPACE_EXPORT",
        "NAMED_EXPORTS", "EXPORT_SPECIFIER", "IMPORT_CLAUSE", "EXTERNAL_MODULE_REFERENCE",
        "JSX_ATTRIBUTE", "JSX_SPREAD_ATTRIBUTE", "JSX_OPENING_ELEMENT", "JSX_CLOSING_ELEMENT",
        "JSX_ELEMENT", "JSX_SELF_CLOSING_ELEMENT", "JSX_TEXT", "JSX_EXPRESSION_CONTAINER",
        "JSX_FRAGMENT",
    )

    /**
     * The kinds of every AST class that implements `Statement` (transitively,
     * i.e. including `Declaration`). The closure for a handler whose only gate
     * is `if (node is Statement)`.
     */
    val STATEMENT_KINDS: IntArray = intArrayOf(
        NodeKind.BLOCK, NodeKind.BREAK_STATEMENT, NodeKind.CLASS_DECLARATION,
        NodeKind.CONTINUE_STATEMENT, NodeKind.DEBUGGER_STATEMENT, NodeKind.DO_STATEMENT,
        NodeKind.EMPTY_STATEMENT, NodeKind.ENUM_DECLARATION, NodeKind.EXPORT_ASSIGNMENT,
        NodeKind.EXPORT_DECLARATION, NodeKind.EXPRESSION_STATEMENT, NodeKind.FOR_IN_STATEMENT,
        NodeKind.FOR_OF_STATEMENT, NodeKind.FOR_STATEMENT, NodeKind.FUNCTION_DECLARATION,
        NodeKind.IF_STATEMENT, NodeKind.IMPORT_DECLARATION, NodeKind.IMPORT_EQUALS_DECLARATION,
        NodeKind.INTERFACE_DECLARATION, NodeKind.LABELED_STATEMENT, NodeKind.MODULE_DECLARATION,
        NodeKind.NOT_EMITTED_STATEMENT, NodeKind.RAW_STATEMENT, NodeKind.RETURN_STATEMENT,
        NodeKind.SWITCH_STATEMENT, NodeKind.THROW_STATEMENT, NodeKind.TRY_STATEMENT,
        NodeKind.TYPE_ALIAS_DECLARATION, NodeKind.VARIABLE_DECLARATION,
        NodeKind.VARIABLE_STATEMENT, NodeKind.WHILE_STATEMENT, NodeKind.WITH_STATEMENT,
    )

    private fun union(vararg parts: IntArray): IntArray =
        parts.flatMap { it.toList() }.distinct().sorted().toIntArray()

    // ── ENTER handlers, in production consult order ─────────────────────────

    val enterNames: Array<String> = arrayOf(
        "ctaSpineEnter", "cpaSpineEnter", "ccetSpineEnter",
        "spineCtaM3PropertyAnchor", "spineCtaM3BodyWalkerAnchor", "spineCtaM3StatementAnchor",
        "spineArithEnterNode", "spineIanyEnterNode", "spineDaEnterNode", "spineOsEnterNode",
        "spinePdEnterNode", "spineItEnterNode", "spineFpEnterNode", "spineAiEnterNode",
        "spineSyEnterNode", "spineCoEnterNode", "spineB94EnterNode", "spineCeEnterNode",
        "spinePmrEnterNode", "spinePiEnterNode", "spineGxEnterNode", "spineAcEnterNode",
        "spineEvEnterNode", "spineUyEnterNode", "spineSrEnterNode", "spineIaEnterNode",
        "spineTdEnterNode", "spineExEnterNode", "spineSmEnterNode", "spineClEnterNode",
        "spineSuEnterNode", "spineTcEnterNode", "spineDelEnterNode", "spineCpEnterNode",
        "spineAbEnterNode", "spineIyEnterNode", "spineAaEnterNode", "spineIdcEnterNode",
        "spineNaEnterNode", "spineAfEnterNode", "spineTpoEnterNode", "spineUbdEnterNode",
        "spineCaEnterNode", "spineAtEnterNode", "spineNuEnterNode", "spineCmEnterNode",
    )

    /** `null` = OPEN (always run). Index-aligned with [enterNames]. */
    val enterClosure: Array<IntArray?> = arrayOf(
        /*  0 ctaSpineEnter            */ null,
        /*  1 cpaSpineEnter            */ null,
        /*  2 ccetSpineEnter           */ null,
        /*  3 spineCtaM3PropertyAnchor */ intArrayOf(NodeKind.PROPERTY_DECLARATION),
        /*  4 spineCtaM3BodyWalker     */ intArrayOf(NodeKind.BLOCK),
        /*  5 spineCtaM3StatementAnchr */ intArrayOf(
            NodeKind.VARIABLE_STATEMENT, NodeKind.EXPRESSION_STATEMENT,
            NodeKind.RETURN_STATEMENT, NodeKind.IF_STATEMENT),
        /*  6 spineArithEnterNode      */ null,
        /*  7 spineIanyEnterNode       */ null,
        /*  8 spineDaEnterNode         */ union(STATEMENT_KINDS, intArrayOf(
            NodeKind.SOURCE_FILE, NodeKind.MODULE_BLOCK)),
        /*  9 spineOsEnterNode         */ null,
        /* 10 spinePdEnterNode         */ intArrayOf(
            NodeKind.SOURCE_FILE, NodeKind.BLOCK, NodeKind.MODULE_BLOCK,
            NodeKind.VARIABLE_STATEMENT, NodeKind.EXPRESSION_STATEMENT,
            NodeKind.RETURN_STATEMENT, NodeKind.IF_STATEMENT),
        /* 11 spineItEnterNode         */ intArrayOf(
            NodeKind.IDENTIFIER, NodeKind.PROPERTY_ACCESS_EXPRESSION),
        /* 12 spineFpEnterNode         */ intArrayOf(NodeKind.CALL_EXPRESSION),
        /* 13 spineAiEnterNode         */ intArrayOf(NodeKind.NEW_EXPRESSION),
        /* 14 spineSyEnterNode         */ intArrayOf(
            NodeKind.BINARY_EXPRESSION, NodeKind.PREFIX_UNARY_EXPRESSION,
            NodeKind.TEMPLATE_EXPRESSION),
        /* 15 spineCoEnterNode         */ intArrayOf(
            NodeKind.TYPE_ASSERTION_EXPRESSION, NodeKind.AS_EXPRESSION),
        /* 16 spineB94EnterNode        */ intArrayOf(
            NodeKind.VARIABLE_STATEMENT, NodeKind.FOR_STATEMENT, NodeKind.FOR_IN_STATEMENT,
            NodeKind.FOR_OF_STATEMENT, NodeKind.ARROW_FUNCTION, NodeKind.FUNCTION_EXPRESSION,
            NodeKind.METHOD_DECLARATION, NodeKind.CONSTRUCTOR, NodeKind.SET_ACCESSOR),
        /* 17 spineCeEnterNode         */ intArrayOf(
            NodeKind.ENUM_DECLARATION, NodeKind.IDENTIFIER, NodeKind.ELEMENT_ACCESS_EXPRESSION),
        /* 18 spinePmrEnterNode        */ intArrayOf(
            NodeKind.FUNCTION_DECLARATION, NodeKind.FUNCTION_EXPRESSION, NodeKind.ARROW_FUNCTION,
            NodeKind.METHOD_DECLARATION, NodeKind.GET_ACCESSOR, NodeKind.SET_ACCESSOR,
            NodeKind.CONSTRUCTOR, NodeKind.EXPRESSION_STATEMENT,
            NodeKind.PROPERTY_ACCESS_EXPRESSION, NodeKind.BINARY_EXPRESSION),
        /* 19 spinePiEnterNode         */ intArrayOf(
            NodeKind.CLASS_DECLARATION, NodeKind.CLASS_EXPRESSION),
        /* 20 spineGxEnterNode         */ intArrayOf(NodeKind.BINARY_EXPRESSION),
        /* 21 spineAcEnterNode         */ intArrayOf(
            NodeKind.FUNCTION_DECLARATION, NodeKind.METHOD_DECLARATION, NodeKind.CONSTRUCTOR,
            NodeKind.SET_ACCESSOR, NodeKind.ARROW_FUNCTION, NodeKind.FUNCTION_EXPRESSION),
        /* 22 spineEvEnterNode         */ intArrayOf(
            NodeKind.SOURCE_FILE, NodeKind.BLOCK, NodeKind.MODULE_BLOCK),
        /* 23 spineUyEnterNode         */ intArrayOf(
            NodeKind.CLASS_DECLARATION, NodeKind.INTERFACE_DECLARATION,
            NodeKind.TYPE_ALIAS_DECLARATION, NodeKind.YIELD_EXPRESSION),
        /* 24 spineSrEnterNode         */ intArrayOf(NodeKind.IDENTIFIER),
        /* 25 spineIaEnterNode         */ intArrayOf(NodeKind.BINARY_EXPRESSION),
        /* 26 spineTdEnterNode         */ intArrayOf(
            NodeKind.FUNCTION_DECLARATION, NodeKind.CLASS_DECLARATION,
            NodeKind.INTERFACE_DECLARATION, NodeKind.TYPE_ALIAS_DECLARATION,
            NodeKind.ARROW_FUNCTION, NodeKind.FUNCTION_EXPRESSION, NodeKind.CLASS_EXPRESSION,
            NodeKind.METHOD_DECLARATION, NodeKind.FUNCTION_TYPE, NodeKind.CONSTRUCTOR_TYPE),
        /* 27 spineExEnterNode         */ intArrayOf(NodeKind.PROPERTY_ACCESS_EXPRESSION),
        /* 28 spineSmEnterNode         */ intArrayOf(
            NodeKind.VARIABLE_STATEMENT, NodeKind.FUNCTION_DECLARATION,
            NodeKind.INTERFACE_DECLARATION, NodeKind.FUNCTION_EXPRESSION,
            NodeKind.ARROW_FUNCTION, NodeKind.BINARY_EXPRESSION,
            NodeKind.PREFIX_UNARY_EXPRESSION, NodeKind.POSTFIX_UNARY_EXPRESSION,
            NodeKind.FOR_STATEMENT, NodeKind.TRY_STATEMENT),
        /* 29 spineClEnterNode         */ intArrayOf(NodeKind.BINARY_EXPRESSION),
        /* 30 spineSuEnterNode         */ intArrayOf(NodeKind.OBJECT_LITERAL_EXPRESSION),
        /* 31 spineTcEnterNode         */ intArrayOf(
            NodeKind.TYPE_ASSERTION_EXPRESSION, NodeKind.AS_EXPRESSION),
        /* 32 spineDelEnterNode        */ intArrayOf(NodeKind.DELETE_EXPRESSION),
        /* 33 spineCpEnterNode         */ intArrayOf(
            NodeKind.CLASS_DECLARATION, NodeKind.CLASS_EXPRESSION),
        /* 34 spineAbEnterNode         */ intArrayOf(
            NodeKind.CLASS_DECLARATION, NodeKind.CLASS_EXPRESSION),
        /* 35 spineIyEnterNode         */ intArrayOf(NodeKind.YIELD_EXPRESSION),
        /* 36 spineAaEnterNode         */ intArrayOf(
            NodeKind.CLASS_DECLARATION, NodeKind.CLASS_EXPRESSION),
        /* 37 spineIdcEnterNode        */ intArrayOf(
            NodeKind.PREFIX_UNARY_EXPRESSION, NodeKind.POSTFIX_UNARY_EXPRESSION),
        /* 38 spineNaEnterNode         */ intArrayOf(NodeKind.NEW_EXPRESSION),
        /* 39 spineAfEnterNode         */ intArrayOf(NodeKind.IDENTIFIER),
        /* 40 spineTpoEnterNode        */ intArrayOf(
            NodeKind.CLASS_DECLARATION, NodeKind.FUNCTION_DECLARATION,
            NodeKind.METHOD_DECLARATION, NodeKind.CONSTRUCTOR, NodeKind.GET_ACCESSOR,
            NodeKind.SET_ACCESSOR, NodeKind.VARIABLE_STATEMENT,
            NodeKind.PROPERTY_ACCESS_EXPRESSION, NodeKind.CALL_EXPRESSION,
            NodeKind.NEW_EXPRESSION),
        /* 41 spineUbdEnterNode        */ union(STATEMENT_KINDS, intArrayOf(
            NodeKind.FOR_STATEMENT, NodeKind.FOR_IN_STATEMENT, NodeKind.FOR_OF_STATEMENT)),
        /* 42 spineCaEnterNode         */ union(STATEMENT_KINDS, intArrayOf(
            NodeKind.SOURCE_FILE, NodeKind.MODULE_BLOCK, NodeKind.CASE_CLAUSE,
            NodeKind.DEFAULT_CLAUSE, NodeKind.BINARY_EXPRESSION,
            NodeKind.PREFIX_UNARY_EXPRESSION, NodeKind.POSTFIX_UNARY_EXPRESSION,
            NodeKind.REGULAR_EXPRESSION_LITERAL_NODE)),
        /* 43 spineAtEnterNode         */ intArrayOf(
            NodeKind.IF_STATEMENT, NodeKind.WHILE_STATEMENT, NodeKind.DO_STATEMENT,
            NodeKind.FOR_STATEMENT, NodeKind.BINARY_EXPRESSION,
            NodeKind.CONDITIONAL_EXPRESSION, NodeKind.PREFIX_UNARY_EXPRESSION),
        /* 44 spineNuEnterNode         */ intArrayOf(
            NodeKind.BINARY_EXPRESSION, NodeKind.PROPERTY_ACCESS_EXPRESSION,
            NodeKind.ELEMENT_ACCESS_EXPRESSION, NodeKind.FOR_OF_STATEMENT),
        /* 45 spineCmEnterNode         */ intArrayOf(NodeKind.BINARY_EXPRESSION),
    )

    // ── LEAVE handlers, in production consult order ─────────────────────────

    val leaveNames: Array<String> = arrayOf(
        "ctaSpineLeave", "cpaSpineLeave", "ccetSpineLeave", "spineArithLeaveNode",
        "spineIanyLeaveNode", "spineDaLeaveNode", "spineOsLeaveNode", "spinePdLeaveNode",
        "spineCaLeaveNode", "spineNpLeaveNode", "spineIrLeaveNode", "spinePmrLeaveNode",
        "spineTpoLeaveNode",
    )

    /** `null` = OPEN (always run). Index-aligned with [leaveNames]. */
    val leaveClosure: Array<IntArray?> = arrayOf(
        /*  0 ctaSpineLeave       */ null,
        /*  1 cpaSpineLeave       */ null,
        /*  2 ccetSpineLeave      */ null,
        // OPEN: its own-kind `when` is followed by a frame pop keyed on
        // `frames.last().node === node` (any kind an edge frame was pushed at).
        /*  3 spineArithLeaveNode */ null,
        /*  4 spineIanyLeaveNode  */ null,
        /*  5 spineDaLeaveNode    */ null,
        /*  6 spineOsLeaveNode    */ null,
        /*  7 spinePdLeaveNode    */ null,
        /*  8 spineCaLeaveNode    */ null,
        // OPEN: the `else` arm reaches EVERY Expression kind (a while/do
        // condition's own leave), not just the two loop kinds.
        /*  9 spineNpLeaveNode    */ null,
        /* 10 spineIrLeaveNode    */ intArrayOf(
            NodeKind.FUNCTION_DECLARATION, NodeKind.FUNCTION_EXPRESSION,
            NodeKind.ARROW_FUNCTION, NodeKind.METHOD_DECLARATION, NodeKind.GET_ACCESSOR),
        /* 11 spinePmrLeaveNode   */ null,
        /* 12 spineTpoLeaveNode   */ null,
    )

    val enterCount: Int get() = enterNames.size
    val leaveCount: Int get() = leaveNames.size

    /** `enterTable[kind]` = handler ids to run for that kind (OPEN ones included). */
    val enterTable: Array<IntArray> = buildTable(enterClosure)
    val leaveTable: Array<IntArray> = buildTable(leaveClosure)

    private fun buildTable(closure: Array<IntArray?>): Array<IntArray> =
        Array(KINDS) { kind ->
            val ids = ArrayList<Int>(8)
            for (h in closure.indices) {
                val c = closure[h]
                if (c == null || kind in c) ids.add(h)
            }
            ids.toIntArray()
        }

    // ── probe accumulators (single-threaded: the check spine) ───────────────

    var enterConsult: Array<LongArray> = Array(enterNames.size) { LongArray(KINDS) }
    var enterWork: Array<LongArray> = Array(enterNames.size) { LongArray(KINDS) }
    var enterNanos: Array<LongArray> = Array(enterNames.size) { LongArray(KINDS) }
    var leaveConsult: Array<LongArray> = Array(leaveNames.size) { LongArray(KINDS) }
    var leaveWork: Array<LongArray> = Array(leaveNames.size) { LongArray(KINDS) }
    var leaveNanos: Array<LongArray> = Array(leaveNames.size) { LongArray(KINDS) }

    /** Nodes seen by the spine, per kind — the SINGLE-THREADED population (the
     *  parse-time `nodeKindHistogram` is racy; see the class doc). */
    var kindNodes: LongArray = LongArray(KINDS)

    /** Nanos of the whole enter prologue / the `when(kindId)` tail, per kind. */
    var prologueNanos: LongArray = LongArray(KINDS)
    var tailNanos: LongArray = LongArray(KINDS)

    /** Cost of one probe timestamp pair, measured by an empty handler slot. */
    var probeOverheadNanos: Long = 0
    var probeOverheadCalls: Long = 0

    /** Set by the by-id loop so a handler's [work] call knows who is running. */
    var current: Int = -1
    var currentKind: Int = 0
    var currentIsLeave: Boolean = false

    fun reset() {
        enterConsult = Array(enterNames.size) { LongArray(KINDS) }
        enterWork = Array(enterNames.size) { LongArray(KINDS) }
        enterNanos = Array(enterNames.size) { LongArray(KINDS) }
        leaveConsult = Array(leaveNames.size) { LongArray(KINDS) }
        leaveWork = Array(leaveNames.size) { LongArray(KINDS) }
        leaveNanos = Array(leaveNames.size) { LongArray(KINDS) }
        kindNodes = LongArray(KINDS)
        prologueNanos = LongArray(KINDS)
        tailNanos = LongArray(KINDS)
        probeOverheadNanos = 0
        probeOverheadCalls = 0
        current = -1
    }

    /**
     * Called by an OPEN handler at each point where it does something
     * observable (emits, pushes/pops a frame, writes an ambient map). A no-op
     * unless a probe loop is running, so a production run pays one static
     * field read on a path that already passed the handler's own gate.
     */
    fun work() {
        val h = current
        if (h < 0) return
        if (currentIsLeave) leaveWork[h][currentKind]++ else enterWork[h][currentKind]++
    }

    /** Per-kind report: nodes, prologue vs tail nanos, handlers run vs 46. */
    fun report(): String = buildString {
        appendLine("== (DISPATCH.1) spine handler probe ==")
        val totalNodes = kindNodes.sum()
        appendLine("nodes (single-threaded spine): $totalNodes")
        var consultsNow = 0L
        var consultsTabled = 0L
        for (k in 0 until KINDS) {
            consultsNow += kindNodes[k] * (enterCount + leaveCount)
            consultsTabled += kindNodes[k] * (enterTable[k].size + leaveTable[k].size)
        }
        appendLine(
            "handler consultations: now=$consultsNow tabled=$consultsTabled " +
                "(${if (consultsNow > 0) 100 - consultsTabled * 100 / consultsNow else 0}% removed)"
        )
        if (totalNodes > 0) {
            appendLine(
                "handlers per node: now=${(enterCount + leaveCount)} " +
                    "tabled=${consultsTabled.toDouble() / totalNodes}"
            )
        }
        val ovh = if (probeOverheadCalls > 0) probeOverheadNanos / probeOverheadCalls else 0L
        appendLine("probe timestamp-pair overhead: $ovh ns (over $probeOverheadCalls calls)")
        // THE decisive number: the time the table would remove is the time
        // handlers spend on kinds OUTSIDE their closure — NOT their total time,
        // most of which is the work they exist to do. Nanos are probe-inflated
        // (a `when(h)` call plus one timestamp pair per handler per node), so
        // this is an UPPER bound on the production win.
        var removable = 0L
        var removableCalls = 0L
        var kept = 0L
        for (h in enterClosure.indices) {
            val c = enterClosure[h]
            for (k in 0 until KINDS) {
                val net = enterNanos[h][k] - ovh * enterConsult[h][k]
                if (c != null && k !in c) { removable += net; removableCalls += enterConsult[h][k] }
                else kept += net
            }
        }
        for (h in leaveClosure.indices) {
            val c = leaveClosure[h]
            for (k in 0 until KINDS) {
                val net = leaveNanos[h][k] - ovh * leaveConsult[h][k]
                if (c != null && k !in c) { removable += net; removableCalls += leaveConsult[h][k] }
                else kept += net
            }
        }
        appendLine(
            "TABLE PRIZE (upper bound): ${removable / 1_000_000} ms over $removableCalls " +
                "skipped consultations = ${if (removableCalls > 0) removable / removableCalls else 0} ns each; " +
                "kept ${kept / 1_000_000} ms"
        )
        appendLine("-- per kind (top 20 by nodes) --")
        val order = (0 until KINDS).sortedByDescending { kindNodes[it] }.take(20)
        for (k in order) {
            if (kindNodes[k] == 0L) continue
            appendLine(
                "  ${kindNames[k]}: ${kindNodes[k]} nodes, " +
                    "prologue=${prologueNanos[k] / 1_000_000}ms tail=${tailNanos[k] / 1_000_000}ms, " +
                    "enter ${enterTable[k].size}/$enterCount leave ${leaveTable[k].size}/$leaveCount"
            )
        }
        appendLine("-- OPEN handlers: kinds observed doing work --")
        for (h in enterClosure.indices) {
            if (enterClosure[h] != null) continue
            val ks = (0 until KINDS).filter { enterWork[h][it] > 0 }
            appendLine("  enter ${enterNames[h]}: ${ks.size} kinds ${ks.joinToString(",") { kindNames[it] }}")
        }
        for (h in leaveClosure.indices) {
            if (leaveClosure[h] != null) continue
            val ks = (0 until KINDS).filter { leaveWork[h][it] > 0 }
            appendLine("  leave ${leaveNames[h]}: ${ks.size} kinds ${ks.joinToString(",") { kindNames[it] }}")
        }
        appendLine("-- per handler nanos (probe-inflated; subtract $ovh ns/call) --")
        val rows = ArrayList<Triple<String, Long, Long>>()
        for (h in enterNames.indices) {
            val ns = enterNanos[h].sum(); val c = enterConsult[h].sum()
            rows.add(Triple("enter " + enterNames[h], ns, c))
        }
        for (h in leaveNames.indices) {
            val ns = leaveNanos[h].sum(); val c = leaveConsult[h].sum()
            rows.add(Triple("leave " + leaveNames[h], ns, c))
        }
        for ((n, ns, c) in rows.sortedByDescending { it.second }) {
            val net = ns - ovh * c
            appendLine(
                "  ${n.padEnd(34)} ${(ns / 1_000_000).toString().padStart(6)} ms raw, " +
                    "${(net / 1_000_000).toString().padStart(6)} ms net over $c calls"
            )
        }
    }

    /** Machine-readable dump: one line per (phase, handler, kind) with data. */
    fun csv(): String = buildString {
        appendLine("phase,handler,kind,nodes,consults,works,nanos")
        for (h in enterNames.indices) for (k in 0 until KINDS) {
            if (enterConsult[h][k] == 0L) continue
            appendLine(
                "enter,${enterNames[h]},${kindNames[k]},${kindNodes[k]}," +
                    "${enterConsult[h][k]},${enterWork[h][k]},${enterNanos[h][k]}"
            )
        }
        for (h in leaveNames.indices) for (k in 0 until KINDS) {
            if (leaveConsult[h][k] == 0L) continue
            appendLine(
                "leave,${leaveNames[h]},${kindNames[k]},${kindNodes[k]}," +
                    "${leaveConsult[h][k]},${leaveWork[h][k]},${leaveNanos[h][k]}"
            )
        }
    }
}

/**
 * (SPINE.1) step (a) — the opt-in INTRA-handler attribution for the two
 * hottest spine leave handlers, `cpaSpineLeave` (4,366 ms) and
 * `ccetSpineLeave` (3,046 ms), together 40% of the round-732 spine.
 *
 * ## Why a second probe object
 *
 * [SpineDispatch] answers *which handler*; it cannot answer *which part of a
 * handler*, and round 732's lesson is that the guess about "which part" is the
 * step that goes wrong. Both handlers are a SEQUENCE of independent top-level
 * sections, so the cheapest sound attribution is a running timestamp split
 * between them ([split]) plus dedicated timers around the two ancestor climbs
 * (`cpaM2ChainOk`, `ccetM3ChainOk`) and the two frame-ambient installs, which
 * are the parts a future optimisation would target.
 *
 * ## Behaviour-free when off (INV.0)
 *
 * [mode] is [OFF] in production; every entry point returns immediately on one
 * static field read, and [t]/[split]/[hit] are `inline` so a production call
 * is a load-and-branch, never a call. Pinned by `SpineSectionProbeTest`.
 *
 * ## Reading the report
 *
 * The section rows PARTITION the handler (they are disjoint spans of one
 * call). The `of which` rows do NOT — they are nested inside the sections and
 * are reported so the split between *deciding to work* (the climbs), *setting
 * up to work* (the ambient install) and *the work itself* is explicit. Nanos
 * are probe-inflated by one timestamp pair per split, exactly as round 732's
 * per-handler nanos were; [overheadNanos] measures the pair so the report can
 * state the inflation. They are sound for RELATIVE attribution only.
 */
object SpineSections {

    const val OFF = 0
    const val ON = 1

    /** Opt-in; [OFF] in production. Set by `--spineSections`. */
    var mode: Int = OFF

    // Disjoint sections of cpaSpineLeave, in source order.
    const val CPA_ANCHOR = 0
    const val CPA_OWNER = 1
    const val CPA_EWTA = 2
    const val CPA_PROPDECL = 3
    const val CPA_RESTORES = 4
    const val CPA_VARDECL = 5
    const val CPA_POP = 6
    // Disjoint sections of ccetSpineLeave, in source order.
    const val CCET_RESTORES = 7
    const val CCET_CALL = 8
    const val CCET_VARDECL = 9
    const val CCET_POP = 10
    // Nested sub-measures (inside the sections above; NOT part of the partition).
    const val CPA_CHAINOK = 11
    const val CPA_STMTPOS = 12
    const val CCET_CHAINOK = 13
    const val CPA_AMBIENT = 14
    const val CCET_AMBIENT = 15
    const val CPA_INSTALL = 16
    const val CCET_INSTALL = 17

    /**
     * The in-situ calibration: an EMPTY span opened and closed back-to-back at
     * the top of `cpaSpineLeave`, once per node. Its mean is the cost of one
     * probe timestamp pair under the same JIT state and cache pressure as the
     * real splits — unlike a startup loop, which measures a cold interpreter
     * (the first draft read 40 µs/pair and made every net figure negative).
     */
    const val OVERHEAD = 18
    const val NONE = -1

    const val N = 19

    val names: Array<String> = arrayOf(
        "cpa: anchor stmt (m3a/m3b)", "cpa: owner cond/subject (m3b)",
        "cpa: heritage EWTA (m3c)", "cpa: PropertyDeclaration init (m3c)",
        "cpa: loop-var restores", "cpa: VariableDeclaration recordings",
        "cpa: frame pop",
        "ccet: override restores", "ccet: call/new/tagged anchor (m3)",
        "ccet: VariableDeclaration recordings", "ccet: frame pop",
        "  of which cpaM2ChainOk", "  of which cpaM2StmtPosition",
        "  of which ccetM3ChainOk",
        "  of which withCpaFrameAmbient (install+work)",
        "  of which withCcetFrameAmbient (install+work)",
        "  of which cpa ambient install+restore only",
        "  of which ccet ambient install+restore only",
        "  probe timestamp pair (in situ)",
    )

    /** The first index that is a nested sub-measure rather than a partition row. */
    const val FIRST_NESTED = CPA_CHAINOK

    var nanos: Array<LongArray> = Array(N) { LongArray(SpineDispatch.KINDS) }
    var calls: Array<LongArray> = Array(N) { LongArray(SpineDispatch.KINDS) }

    /** Times the section's gate PASSED, i.e. it actually did its work. */
    var hits: LongArray = LongArray(N)

    /** Ancestor-chain length above the climb's start node, summed per climb —
     *  an UPPER bound on the steps the climb takes (it may return early).
     *  Probe-only: computed in the timing wrapper, never in the climb. */
    var climbDepth: LongArray = LongArray(N)

    /** Cost of one probe timestamp pair, measured by an empty split. */
    var overheadNanos: Long = 0
    var overheadCalls: Long = 0

    fun reset() {
        nanos = Array(N) { LongArray(SpineDispatch.KINDS) }
        calls = Array(N) { LongArray(SpineDispatch.KINDS) }
        hits = LongArray(N)
        climbDepth = LongArray(N)
        overheadNanos = 0
        overheadCalls = 0
    }

    // The four entry points below are DELIBERATELY inline despite carrying no
    // function-typed parameter: they sit on the per-node spine path, so the
    // production cost must be one static read plus a not-taken branch, never a
    // call. (Round 732's `SpineDispatch.work()` is a call because it is only
    // reached at a handler's rare work points.)

    /** A timestamp, or 0 when off. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun t(): Long = if (mode == OFF) 0L else PassTiming.nowNanos()

    /**
     * Close the span that started at [t0], attribute it to [sec] for [kind],
     * and return a fresh timestamp for the next section. Returns 0 when off.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun split(sec: Int, kind: Int, t0: Long): Long {
        if (mode == OFF) return 0L
        val now = PassTiming.nowNanos()
        nanos[sec][kind] += now - t0
        calls[sec][kind]++
        return now
    }

    /** Close a NESTED span without starting a new one. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun close(sec: Int, kind: Int, t0: Long) {
        if (mode == OFF) return
        nanos[sec][kind] += PassTiming.nowNanos() - t0
        calls[sec][kind]++
    }

    /** Record that [sec]'s gate passed (it did its work, not just its test). */
    @Suppress("NOTHING_TO_INLINE")
    inline fun hit(sec: Int) {
        if (mode == OFF) return
        hits[sec]++
    }

    /** Probe-only: add the ancestor depth above [node] to [sec]'s climb budget. */
    fun climb(sec: Int, node: Node) {
        if (mode == OFF) return
        var d = 0L
        var c: Node? = (node as NodeBase).parent
        while (c != null) { d++; c = (c as NodeBase).parent }
        climbDepth[sec] += d
    }

    /** Calibrate [overheadNanos] with an empty timestamp pair. */
    fun calibrate() {
        if (mode == OFF) return
        val a = PassTiming.nowNanos()
        overheadNanos += PassTiming.nowNanos() - a
        overheadCalls++
    }

    fun report(): String = buildString {
        appendLine("== (SPINE.1) intra-handler attribution: cpaSpineLeave + ccetSpineLeave ==")
        val ovhCalls = calls[OVERHEAD].sum()
        val ovh = if (ovhCalls > 0) nanos[OVERHEAD].sum() / ovhCalls else 0L
        val startup = if (overheadCalls > 0) overheadNanos / overheadCalls else 0L
        appendLine(
            "probe timestamp-pair overhead: $ovh ns in situ (over $ovhCalls calls); " +
                "$startup ns at startup (cold, over $overheadCalls calls — NOT used)"
        )
        var partition = 0L
        var raw = 0L
        for (s in 0 until FIRST_NESTED) { partition += net(s, ovh); raw += nanos[s].sum() }
        appendLine(
            "partition total (cpa+ccet leave): ${partition / 1_000_000} ms net, " +
                "${raw / 1_000_000} ms raw"
        )
        appendLine("-- sections (disjoint; ms net of probe overhead) --")
        for (s in 0 until N) {
            val c = calls[s].sum()
            if (c == 0L) continue
            if (s == FIRST_NESTED) appendLine("-- nested sub-measures (INSIDE the sections above) --")
            val ns = net(s, ovh)
            val depth = climbDepth[s]
            appendLine(
                "  ${names[s].padEnd(44)} ${(ns / 1_000_000).toString().padStart(6)} ms net " +
                    "(${(nanos[s].sum() / 1_000_000).toString().padStart(6)} raw) " +
                    "over ${c.toString().padStart(9)} calls = ${
                        if (c > 0) ns / c else 0
                    } ns each, hits=${hits[s]}" +
                    if (depth > 0) ", meanAncestorDepth=${depth / c}" else ""
            )
        }
        appendLine("-- top kinds per section (ms net) --")
        for (s in 0 until N) {
            val c = calls[s].sum()
            if (c == 0L) continue
            val order = (0 until SpineDispatch.KINDS)
                .filter { calls[s][it] > 0 }
                .sortedByDescending { nanos[s][it] }
                .take(5)
            appendLine("  ${names[s].trim()}: " + order.joinToString(", ") {
                "${SpineDispatch.kindNames[it]}=${
                    (nanos[s][it] - ovh * calls[s][it]) / 1_000_000
                }ms/${calls[s][it]}"
            })
        }
    }

    private fun net(sec: Int, ovh: Long): Long {
        var total = 0L
        for (k in 0 until SpineDispatch.KINDS) total += nanos[sec][k] - ovh * calls[sec][k]
        return total
    }

    /** Machine-readable dump: one line per (section, kind) with data. */
    fun csv(): String = buildString {
        appendLine("section,kind,calls,nanos")
        for (s in 0 until N) for (k in 0 until SpineDispatch.KINDS) {
            if (calls[s][k] == 0L) continue
            appendLine("\"${names[s].trim()}\",${SpineDispatch.kindNames[k]},${calls[s][k]},${nanos[s][k]}")
        }
    }
}

/**
 * (CALL.1) step (a) — the opt-in INTRA-FUNCTION attribution for
 * `checkSingleCallExpressionTypes`.
 *
 * ## Why a third probe object
 *
 * [SpineDispatch] answers *which handler*, [SpineSections] *which part of a
 * handler*; round 733 used them to land on one function. `ccetSpineLeave`'s
 * call anchor costs **2,931 ms over 52,413 CallExpression nodes = 53.6 µs
 * each** — a 920-line straight-line function with 18 `diagnostics.add` sites,
 * run in full for every call in the program, and the largest per-node cost
 * measured anywhere in this compiler. The open question is whether that is
 * *type-system work* (signature resolution + argument relations) or *emission
 * pre-work* the never-firing sites pay before they know they will not fire.
 * Guessing has been falsified twice (rounds 732 and 733), so this measures.
 *
 * ## The mechanism, and why it differs from [SpineSections]
 *
 * The function is a SEQUENCE of independent top-level sections, so the
 * attribution is again a running timestamp ([at]) split between them. But
 * unlike a spine handler it has ~20 early `return`s, so a section can be left
 * without reaching the next boundary. Hence the running section lives in the
 * object ([cur]/[curT]) and is closed by [end] from the wrapper's `finally`.
 * Two consequences worth stating:
 *
 * * `calls[s]` is the number of invocations that REACHED section `s`, so the
 *   DROP between two consecutive sections is exactly the number of
 *   invocations that returned inside the earlier one. The exit profile is
 *   therefore free — no `hit` counters needed.
 * * [depth] makes a re-entrant invocation record nothing rather than corrupt
 *   the running section (the function's other caller,
 *   `checkCallTypesInExpr`, is leaf machinery that could in principle nest).
 *
 * ## Behaviour-free when off (INV.0)
 *
 * [mode] is [OFF] in production: the wrapper branches once on it and
 * otherwise calls the untouched core directly — no `try`/`finally`, no
 * bookkeeping. Every entry point returns on one static field read and is
 * `inline`. Pinned by `CallSectionProbeTest`.
 *
 * ## Reading the report
 *
 * Sections `0 until FIRST_NESTED` PARTITION one invocation. The rest are
 * nested INSIDE those sections and are reported separately. Nanos are
 * probe-inflated by one timestamp read per boundary — [OVERHEAD] measures an
 * empty section on the real path, so the report can state the inflation.
 * Sound for RELATIVE attribution only, exactly as in [SpineSections].
 */
object CallSections {

    const val OFF = 0
    const val ON = 1

    /** Opt-in; [OFF] in production. Set by `--callSections`. */
    var mode: Int = OFF

    // ── the disjoint sections of checkSingleCallExpressionTypes, in source order
    /** B216 dependent indexed-access constraint (property-access callees). */
    const val B216 = 0
    /** `reduce<U>` callback `keyof X` mismatch (property-access callees). */
    const val REDUCE_KEYOF = 1
    /** Tuple-union `.filter` optional-element TS18048 (property-access callees). */
    const val TUPLE_FILTER = 2
    /** `compose`-chain `.map` member access (property-access callees). */
    const val COMPOSE_CHAIN = 3
    /** `Object.create(<primitive>)` TS2345. */
    const val OBJECT_CREATE = 4
    /** CJS default-as-namespace TS2349 (`cjsDefaultNsShapes`). */
    const val CJS_DEFAULT_NS = 5
    /** `super<T>()` TS2754 + `super(...)` + `super.m(...)` argument checking. */
    const val SUPER = 6
    /** `getCalleeType(expr.expression)` — the callee resolution. */
    const val CALLEE_TYPE = 7
    /** TS2722 for invoking a possibly-undefined OPTIONAL member. */
    const val OPT_MEMBER = 8
    /** TS2347 + the bare `null`/`undefined` callee + the anyType bail. */
    const val EARLY_GATES = 9
    /** The union-callee branch (TS2349 (a)/(b)/(c) + the combined signature). */
    const val UNION_CALLEE = 10
    /** `getCallSignaturesOfType(calleeType)`. */
    const val CALL_SIGS = 11
    /** The whole `signatures.isEmpty()` branch (TS2348/TS6234/TS2349/TS272x). */
    const val NO_SIGS = 12
    /** The explicit-type-argument branch (TS2344/TS2559 + instantiation). */
    const val TYPE_ARGS = 13
    /** The single-signature branch. */
    const val SINGLE_SIG = 14
    /** The overload branch (TS2554 arity + `checkArgumentsAgainstOverloads`). */
    const val OVERLOADS = 15

    /** The first index that is a nested sub-measure rather than a partition row. */
    const val FIRST_NESTED = 16

    // ── nested sub-measures (INSIDE the sections above) ──────────────────────
    /** The TS2348 gate's `binderResults × top-level statements` scan. */
    const val N_TS2348_SCAN = 16
    /** `checkArgumentsAgainstSignature` inside [SINGLE_SIG]. */
    const val N_SINGLE_ARGS = 17
    /** `checkArgumentsAgainstSignature` inside [TYPE_ARGS]. */
    const val N_TYPEARGS_ARGS = 18
    /** `checkArgumentsAgainstOverloads` inside [OVERLOADS]. */
    const val N_OVERLOAD_ARGS = 19
    /** The TS2793 "implementation would have succeeded" probe in [SINGLE_SIG]. */
    const val N_IMPL_RELATED = 20
    /** The five dedicated walkers inside [SINGLE_SIG], as one span. */
    const val N_SINGLE_WALKERS = 21
    /**
     * [B216]..[SUPER] as ONE span — the whole never-firing pre-work gauntlet
     * measured with a single boundary pair instead of seven, so its total is
     * not swamped by seven boundary costs. Undercounts by whatever returns
     * inside it (zero on the compiler profile: every one of those sections
     * reports `returnedIn=0`).
     */
    const val N_PROLOGUE = 22

    /**
     * The wrapper's own transition — [begin] to the core's first boundary,
     * i.e. one non-inlinable call into a 3,587-bytecode method plus the
     * invocation's first timestamp read. Probe-only; not part of the partition
     * and absent in production.
     */
    const val ENTRY = 23

    /**
     * The FIRST empty boundary span of an invocation, kept separate because it
     * is the one that is not steady-state.
     */
    const val OVERHEAD_FIRST = 24

    /**
     * The in-situ calibration: seven further EMPTY spans back-to-back at the
     * top of the core, so the mean is the STEADY-STATE cost of one [at] under
     * the run's real JIT state — never a startup loop (round 733's first draft
     * read 40 µs/pair cold and made every net figure negative) and never a
     * single span, which this round's first draft used and which read ~1 µs
     * against a probe whose whole measured cost is ~30 ms.
     */
    const val OVERHEAD = 25

    const val N = 26

    val names: Array<String> = arrayOf(
        "B216 dependent indexed-access", "reduce<U> keyof callback",
        "tuple-union .filter optional", "compose-chain .map member",
        "Object.create primitive arg", "CJS default-as-namespace",
        "super call / super.method", "getCalleeType",
        "TS2722 optional member", "TS2347 + null/undefined callee + any bail",
        "union callee (TS2349 a/b/c)", "getCallSignaturesOfType",
        "no call signatures branch", "explicit type arguments branch",
        "single signature branch", "overload branch",
        "  of which TS2348 binderResults scan",
        "  of which checkArgumentsAgainstSignature (single)",
        "  of which checkArgumentsAgainstSignature (typeArgs)",
        "  of which checkArgumentsAgainstOverloads",
        "  of which TS2793 impl-would-have-succeeded probe",
        "  of which the five single-sig dedicated walkers",
        "  of which B216..super as ONE span (1 boundary, not 7)",
        "  wrapper transition (probe-only, not production)",
        "  probe boundary, first of the invocation",
        "  probe boundary (in situ, steady state)",
    )

    var nanos: LongArray = LongArray(N)
    var calls: LongArray = LongArray(N)

    /** Invocations of the instrumented function (nested ones excluded). */
    var invocations: Long = 0

    /** The running section and its start timestamp; `-1` = none open. */
    var cur: Int = -1
    var curT: Long = 0
    var depth: Int = 0

    fun reset() {
        nanos = LongArray(N)
        calls = LongArray(N)
        invocations = 0
        cur = -1
        curT = 0
        depth = 0
    }

    // The entry points are `inline` so a production call is a static read plus
    // a not-taken branch rather than a call, matching [SpineSections].

    /** Open the partition for one invocation, starting at [ENTRY]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun begin() {
        if (mode == OFF) return
        depth++
        if (depth != 1) return
        invocations++
        cur = ENTRY
        curT = PassTiming.nowNanos()
    }

    /** Close the running section and start [sec]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun at(sec: Int) {
        if (mode == OFF || depth != 1) return
        val now = PassTiming.nowNanos()
        nanos[cur] += now - curT
        calls[cur]++
        cur = sec
        curT = now
    }

    /** Close whatever section is still open (the invocation may have returned). */
    @Suppress("NOTHING_TO_INLINE")
    inline fun end() {
        if (mode == OFF) return
        if (depth == 1 && cur >= 0) {
            nanos[cur] += PassTiming.nowNanos() - curT
            calls[cur]++
            cur = -1
        }
        depth--
    }

    /** Start a NESTED sub-measure, or 0 when off. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun t(): Long = if (mode == OFF) 0L else PassTiming.nowNanos()

    /** Close a NESTED sub-measure opened at [t0]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun close(sec: Int, t0: Long) {
        if (mode == OFF) return
        nanos[sec] += PassTiming.nowNanos() - t0
        calls[sec]++
    }

    fun report(): String = buildString {
        appendLine("== (CALL.1) intra-function attribution: checkSingleCallExpressionTypes ==")
        appendLine("invocations: $invocations")
        val ovhCalls = calls[OVERHEAD]
        val ovh = if (ovhCalls > 0) nanos[OVERHEAD] / ovhCalls else 0L
        val firstOvh = if (calls[OVERHEAD_FIRST] > 0)
            nanos[OVERHEAD_FIRST] / calls[OVERHEAD_FIRST] else 0L
        appendLine(
            "probe boundary overhead: $ovh ns in situ, steady state (over $ovhCalls empty " +
                "sections); $firstOvh ns for the invocation's FIRST boundary"
        )
        var partition = 0L
        var raw = 0L
        for (s in 0 until FIRST_NESTED) { partition += nanos[s] - ovh * calls[s]; raw += nanos[s] }
        appendLine(
            "partition total: ${partition / 1_000_000} ms net, ${raw / 1_000_000} ms raw"
        )
        appendLine("-- sections (disjoint, source order; ms net of probe overhead) --")
        for (s in 0 until N) {
            val c = calls[s]
            if (s == FIRST_NESTED) appendLine("-- nested sub-measures (INSIDE the sections above) --")
            if (c == 0L) continue
            val ns = nanos[s] - ovh * c
            appendLine(
                "  ${names[s].padEnd(46)} ${(ns / 1_000_000).toString().padStart(5)} ms net " +
                    "(${(nanos[s] / 1_000_000).toString().padStart(5)} raw) reached ${
                        c.toString().padStart(7)
                    } = ${if (c > 0) ns / c else 0} ns each" +
                    if (s < FIRST_NESTED) ", returnedIn=${returnedIn(s)}" else ""
            )
        }
    }

    /**
     * Invocations that left the function inside section [sec]. Sections
     * [B216]..[NO_SIGS] are strictly sequential, so the drop to the next one is
     * the exit count; [TYPE_ARGS] forks into the two mutually exclusive tail
     * branches; everything that reaches a tail branch leaves inside it.
     */
    fun returnedIn(sec: Int): Long = when (sec) {
        in B216 until TYPE_ARGS -> calls[sec] - calls[sec + 1]
        TYPE_ARGS -> calls[TYPE_ARGS] - calls[SINGLE_SIG] - calls[OVERLOADS]
        else -> calls[sec]
    }

    /** Machine-readable dump: one line per section. */
    fun csv(): String = buildString {
        appendLine("section,reached,nanos")
        for (s in 0 until N) {
            if (calls[s] == 0L) continue
            appendLine("\"${names[s].trim()}\",${calls[s]},${nanos[s]}")
        }
    }
}
