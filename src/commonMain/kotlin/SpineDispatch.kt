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
     * (AUDIT.1, round 758) `getCalleeType` split by OUTCOME — the half whose
     * result is discarded three sections later at
     * `calleeType === anyType || calleeType === errorType`.
     *
     * Round 734 recorded "50.6% of invocations bail" (a FREQUENCY) beside
     * "`getCalleeType` costs 474 ms" (a TIME) and inferred that half of that
     * time is wasted. That inference is only valid if resolution costs the same
     * on both sides, which nothing measured. These two rows measure it.
     */
    const val N_CALLEE_BAIL = 23

    /** [N_CALLEE_BAIL]'s complement — the resolutions the function goes on to use. */
    const val N_CALLEE_LIVE = 24

    /**
     * The wrapper's own transition — [begin] to the core's first boundary,
     * i.e. one non-inlinable call into a 3,587-bytecode method plus the
     * invocation's first timestamp read. Probe-only; not part of the partition
     * and absent in production.
     */
    const val ENTRY = 25

    /**
     * The FIRST empty boundary span of an invocation, kept separate because it
     * is the one that is not steady-state.
     */
    const val OVERHEAD_FIRST = 26

    /**
     * The in-situ calibration: seven further EMPTY spans back-to-back at the
     * top of the core, so the mean is the STEADY-STATE cost of one [at] under
     * the run's real JIT state — never a startup loop (round 733's first draft
     * read 40 µs/pair cold and made every net figure negative) and never a
     * single span, which this round's first draft used and which read ~1 µs
     * against a probe whose whole measured cost is ~30 ms.
     */
    const val OVERHEAD = 27

    /**
     * (ENGINE.2g) round 793 — the candidate prologue PRE-GATE's own cost, one
     * span per invocation. Probe-only: in production the gate is called
     * unbracketed, so this row prices the instrument's subject, not the
     * instrument.
     */
    const val N_PG_GATE = 28

    /** The prologue span for the invocations the gate would SKIP. */
    const val N_PG_PRO_SKIP = 29

    /** The prologue span for the invocations the gate would KEEP. */
    const val N_PG_PRO_KEEP = 30

    /**
     * B216's `getTypeOfExpression(recv)` — the prologue's ONLY type resolution,
     * and (measured) the only side-effecting call any invocation in the skip set
     * can reach. Everything below it in that walker is a pure AST read until a
     * `StringLiteralNode` argument appears, which the gate requires.
     */
    const val N_B216_TYPEOF = 31

    const val N = 32

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
        "  of which getCalleeType -> any/error (result DISCARDED)",
        "  of which getCalleeType -> a usable type",
        "  wrapper transition (probe-only, not production)",
        "  probe boundary, first of the invocation",
        "  probe boundary (in situ, steady state)",
        "  (2g) the prologue pre-gate itself",
        "  (2g) the prologue for calls it would SKIP",
        "  (2g) the prologue for calls it would KEEP",
        "  (2g) B216 getTypeOfExpression(recv)",
    )

    var nanos: LongArray = LongArray(N)
    var calls: LongArray = LongArray(N)

    /** Invocations of the instrumented function (nested ones excluded). */
    var invocations: Long = 0

    // ── (ENGINE.2h) round 795: the deferred TS2793 `implRelated` probe ───────
    /**
     * `--verifyImplRelated`. The TS2793 "the implementation would have
     * succeeded" related-info probe used to run at EVERY single-signature call
     * — `getOverloadImplementationRelated` + `getImplementationSignature` +
     * `allArgumentsMatch`, 23,214 times on the compiler profile — to build a
     * `Diagnostic` that only an argument-error emission ever attaches (57 of
     * those calls reach an emission at all). Round 791's shape: DEFER it to the
     * one site that reads it.
     *
     * The deferral cannot be argued from the emission sites (there is exactly
     * ONE reader, so there is nothing to enumerate); what it CAN change is
     * round 754's cache-mutation ORDER — the deferred evaluation runs after the
     * argument loop has typed the earlier arguments. So under this flag the
     * probe is evaluated at BOTH positions and the **eager** verdict is the one
     * honoured, which makes the verify run reproduce the pre-change binary by
     * construction and its diff column a falsifier rather than a tautology.
     */
    var verifyImplRelated: Boolean = false

    /**
     * The FREE complement control (round 790) for [verifyImplRelated]
     * (`--verifyImplRelatedAll`): the deferred evaluation is ALSO performed —
     * and compared — at the end of every single-signature argument check, not
     * only at the 57 that reach an emission. Same comparison, ~23k-call
     * population instead of 57, so a zero there is a bound worth having; it is
     * not a positive control, which is what [verifyImplRelatedBogus] is for.
     */
    var verifyImplRelatedAll: Boolean = false

    /**
     * The POSITIVE control (`--verifyImplRelatedBogus`): the deferred evaluation
     * drops the `allArgumentsMatch` gate, so it answers "the implementation
     * would have succeeded" wherever a candidate exists at all. Every call whose
     * eager verdict was `null` *because of that gate* must then show up in
     * [implRelatedVerifyDiff]. A zero under this flag means the comparator
     * cannot see a difference and the real run's zero says nothing.
     */
    var verifyImplRelatedBogus: Boolean = false

    /** Calls that evaluated the probe (deferred in production, both under the
     *  verifier). Census, so the row's population is never inferred. */
    var implRelatedCalls: Long = 0
    /** Of those, the ones with an overload IMPLEMENTATION to point at. */
    var implRelatedCandidates: Long = 0
    /** Of those, the ones whose implementation signature was reconstructed. */
    var implRelatedImplSigs: Long = 0
    /** Of those, the ones where `allArgumentsMatch` accepted — i.e. TS2793 is
     *  attachable. The population the eager computation existed to serve. */
    var implRelatedApplied: Long = 0
    /** `--verifyImplRelated` comparisons at the EMISSION site. */
    var implRelatedVerified: Long = 0
    /** Of those, eager and deferred differed at `Diagnostic` granularity. */
    var implRelatedVerifyDiff: Long = 0
    /** `--verifyImplRelatedAll` comparisons over the complement population. */
    var implRelatedVerifiedAll: Long = 0
    /** Of those, eager and deferred differed. */
    var implRelatedVerifyAllDiff: Long = 0

    /** Record one probe evaluation's census. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun noteImplRelated(candidate: Boolean, implSig: Boolean, applied: Boolean) {
        if (mode == OFF && !verifyImplRelated) return
        implRelatedCalls++
        if (candidate) implRelatedCandidates++
        if (implSig) implRelatedImplSigs++
        if (applied) implRelatedApplied++
    }

    /** Record one eager-vs-deferred comparison at the emission site. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun noteImplRelatedVerified(differs: Boolean) {
        implRelatedVerified++
        if (differs) implRelatedVerifyDiff++
    }

    /** Record one eager-vs-deferred comparison over the complement population. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun noteImplRelatedVerifiedAll(differs: Boolean) {
        implRelatedVerifiedAll++
        if (differs) implRelatedVerifyAllDiff++
    }

    // ── (ENGINE.2g) round 793: the prologue pre-gate probe ───────────────────
    /**
     * Compute the candidate prologue pre-gate and HONOUR NOTHING — the prologue
     * runs either way, so a run with this on reproduces the pre-change binary
     * byte for byte and its [pgSkipFired] column is a falsifier rather than a
     * tautology. Set by `--ccetPreGate`.
     */
    var preGateProbe: Boolean = false

    /** The positive control: the gate refutes EVERY call (`--ccetPreGateBogus`). */
    var preGateBogus: Boolean = false

    /** Invocations whose prologue the gate would skip / would keep. */
    var pgSkipCalls: Long = 0
    var pgKeepCalls: Long = 0

    /**
     * Of those, the ones whose prologue actually DID something — emitted a
     * diagnostic, or returned out of the function before reaching the callee
     * resolution. `pgSkipFired` must be 0 for the gate to be sound; it is the
     * number the control has to be able to move.
     */
    var pgSkipFired: Long = 0
    var pgKeepFired: Long = 0

    /**
     * Skip-set invocations whose B216 walk got PAST `getTypeOfExpression(recv)`
     * into the class/method AST reads — i.e. how deep the skipped body could
     * possibly have gone. Pure reads from there on.
     */
    var pgB216Deep: Long = 0

    /** Open prologue span state (probe-only; depth-1 invocations only). */
    var pgOpen: Boolean = false
    var pgSkip: Boolean = false
    var pgT0: Long = 0
    var pgD0: Int = 0

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
        pgSkipCalls = 0; pgKeepCalls = 0
        pgSkipFired = 0; pgKeepFired = 0
        pgB216Deep = 0
        pgOpen = false; pgSkip = false; pgT0 = 0; pgD0 = 0
        implRelatedCalls = 0; implRelatedCandidates = 0
        implRelatedImplSigs = 0; implRelatedApplied = 0
        implRelatedVerified = 0; implRelatedVerifyDiff = 0
        implRelatedVerifiedAll = 0; implRelatedVerifyAllDiff = 0
    }

    /** (ENGINE.2g) open the prologue span for one depth-1 invocation. */
    fun openPreGate(skip: Boolean, t0: Long, d0: Int) {
        if (!preGateProbe || depth != 1) return
        pgOpen = true; pgSkip = skip; pgT0 = t0; pgD0 = d0
        if (skip) pgSkipCalls++ else pgKeepCalls++
    }

    /**
     * (ENGINE.2g) close it. [fired] is true when the prologue emitted or
     * returned out of the function — the two effects a skip would erase.
     */
    fun closePreGate(fired: Boolean) {
        if (!pgOpen) return
        pgOpen = false
        nanos[if (pgSkip) N_PG_PRO_SKIP else N_PG_PRO_KEEP] += PassTiming.nowNanos() - pgT0
        calls[if (pgSkip) N_PG_PRO_SKIP else N_PG_PRO_KEEP]++
        if (fired) { if (pgSkip) pgSkipFired++ else pgKeepFired++ }
    }

    /**
     * Reached from the wrapper's `finally`: a span still open there means the
     * prologue left the function through one of its seven `return`s, which is a
     * firing whether or not it emitted.
     */
    fun closePreGateIfOpen() {
        if (pgOpen) closePreGate(fired = true)
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
        appendLine(
            "(ENGINE.2g) prologue pre-gate refused ${prologueRefused()} of " +
                "${calls[B216]} invocations (the seven walkers did not run)"
        )
        if (preGateProbe) {
            appendLine(
                "(ENGINE.2g) prologue pre-gate${if (preGateBogus) " [BOGUS CONTROL]" else ""}: " +
                    "would SKIP $pgSkipCalls, of those FIRED $pgSkipFired; " +
                    "kept $pgKeepCalls, of those FIRED $pgKeepFired; " +
                    "skip-set B216 reads past the receiver type: $pgB216Deep"
            )
        }
        appendLine(implRelatedReport())
    }

    /** (ENGINE.2h) the deferred TS2793 probe's census + verifier columns. */
    fun implRelatedReport(): String =
        "(ENGINE.2h) TS2793 implRelated probe${if (verifyImplRelatedBogus) " [BOGUS CONTROL]" else ""}: " +
            "evaluated $implRelatedCalls (candidate $implRelatedCandidates, " +
            "implSig $implRelatedImplSigs, APPLIED $implRelatedApplied); " +
            "verified at the emission site $implRelatedVerified, DIFF $implRelatedVerifyDiff; " +
            "complement $implRelatedVerifiedAll, DIFF $implRelatedVerifyAllDiff"

    /**
     * Invocations that left the function inside section [sec]. Sections
     * [B216]..[NO_SIGS] are strictly sequential, so the drop to the next one is
     * the exit count; [TYPE_ARGS] forks into the two mutually exclusive tail
     * branches; everything that reaches a tail branch leaves inside it.
     */
    fun returnedIn(sec: Int): Long = when (sec) {
        // (ENGINE.2g) round 793: [B216] is no longer followed unconditionally by
        // [REDUCE_KEYOF] — the prologue pre-gate sends a refuted invocation
        // straight to [CALLEE_TYPE], and those are `calls[CALLEE_TYPE] -
        // calls[N_PROLOGUE]` (an invocation that COMPLETES the prologue closes
        // both). So the two rows at the gate's edges get their own arithmetic
        // and the rows between them keep the plain difference.
        B216 -> calls[B216] - calls[REDUCE_KEYOF] -
            (calls[CALLEE_TYPE] - calls[N_PROLOGUE])
        SUPER -> calls[SUPER] - calls[N_PROLOGUE]
        in B216 until TYPE_ARGS -> calls[sec] - calls[sec + 1]
        TYPE_ARGS -> calls[TYPE_ARGS] - calls[SINGLE_SIG] - calls[OVERLOADS]
        else -> calls[sec]
    }

    /** (ENGINE.2g) invocations the prologue pre-gate refuted, in production. */
    fun prologueRefused(): Long = calls[CALLEE_TYPE] - calls[N_PROLOGUE]

    /** Machine-readable dump: one line per section. */
    fun csv(): String = buildString {
        appendLine("section,reached,nanos")
        for (s in 0 until N) {
            if (calls[s] == 0L) continue
            appendLine("\"${names[s].trim()}\",${calls[s]},${nanos[s]}")
        }
    }
}

/**
 * (CALL.2)(a): the opt-in intra-function attribution of
 * `checkArgumentsAgainstSignature` — 1,357 ms over 22,145 calls = 61 µs each
 * on the compiler profile (round 734), the largest single measured cost in
 * this compiler and a 1,534-line function.
 *
 * Same construction as [CallSections] — a running-section partition closed by
 * [end] from the wrapper's `finally`, so it survives the function's early
 * exits — with ONE structural difference that matters when reading the
 * report: **most of the sections are inside the per-ARGUMENT loop**, so
 * `calls[s]` counts LOOP ITERATIONS that reached `s`, not invocations. The
 * drop between two consecutive loop sections is therefore the number of
 * iterations that `continue`d inside the earlier one — still free, still the
 * exit profile, but per argument. [invocations] and [iterations] are counted
 * separately so the two populations never get mixed up.
 *
 * ## The question this exists to answer
 *
 * The (CALL.2) prior: *most of the 61 µs is argument TYPE computation, not
 * `checkTypeRelatedTo`.* The decisive rows are the two nested sub-measures
 * [N_GET_TYPE_OF_EXPR] and [N_REL_CALL] — each is a single timestamp pair
 * around a single call, so they are directly comparable to each other
 * regardless of how boundary-inflated the surrounding partition is.
 *
 * ## Calibration ([COARSE])
 *
 * Round 734 recorded two failed calibrations (a cold 922 ns span; a `repeat`
 * loop whose back-edge safepoint poll read 360 ns) and settled on a
 * DIFFERENTIAL: the same code measured at N boundaries and at 1. [COARSE]
 * generalises that — it keeps only the anchors in [coarseAnchor], so every
 * other boundary costs a static read and a not-taken branch instead of a
 * timestamp pair. Running the same profile at [ON] and at [COARSE] and
 * dividing the difference by the extra boundary count (which [ON]'s own
 * `calls` array reports) gives the per-boundary cost with no cold-start and
 * no safepoint artifact. Per-section nanos remain sound for RELATIVE
 * attribution only.
 */
object ArgSections {

    const val OFF = 0
    const val ON = 1

    /** Anchors only — the calibration counterpart of [ON]. See the class doc. */
    const val COARSE = 2

    /** Opt-in; [OFF] in production. Set by `--argSections` / `--argSectionsCoarse`. */
    var mode: Int = OFF

    // ── pre-loop ─────────────────────────────────────────────────────────────
    /** The ten `tryEmit*` prologue walkers (mostly generic-signature gated). */
    const val PRO = 0
    /** `tryInferSingleTypeParamFromArgs` + `instantiateSignature` (17.31a). */
    const val INFER = 1
    /** The three single-type-parameter walkers (B199/B204/B219) + `sig.parameters`. */
    const val PRO2 = 2

    // ── the per-ARGUMENT loop body, in source order ───────────────────────────
    /** Loop top: the arity/spread gates, `getTypeOfSymbol(params[i])`, contextual install. */
    const val L_PARAM = 3
    /** The `argType = try { … } finally { … }` block — argument TYPE computation. */
    const val L_ARGTYPE = 4
    /** Foreign-TP probe, rest-param test, weak target, `expressionTrueEnd`. */
    const val L_PRE = 5
    /** `tryEmitWeakTypeAssignment` (TS2559/TS2560). */
    const val L_WEAK = 6
    /** The arrow-drill / array-literal / objlit-intersection / union walkers. */
    const val L_WALKERS = 7
    /** The 353-line object-literal-vs-`Type.Object` block. */
    const val L_OBJLIT = 8
    /** The TypeParam-null gate and the three nullish-argument blocks. */
    const val L_NULLISH = 9
    /** Object literal vs a `Type.TypeParam` parameter. */
    const val L_OBJLIT_TP = 10
    /** `paramType is Type.TypeParam` — constraint checking. */
    const val L_TYPEPARAM = 11
    /** The argument-kind flags plus the class/interface/index-signature block. */
    const val L_ARGKIND = 12
    /** The `!isSimpleCheckableType(paramType)` block (function-vs-function, 196 lines). */
    const val L_NOTSIMPLE = 13
    /** `forceVoidUndefinedFail` plus the optional-parameter/undefined gates. */
    const val L_TAILGATE = 14
    /** The `checkTypeRelatedTo(argType, paramType, …)` gate and the TS2345 emission. */
    const val L_RELATION = 15

    // ── post-loop ────────────────────────────────────────────────────────────
    /** `checkRestArgsAgainstArrayElementType` behind the no-diagnostic gate. */
    const val POST = 16

    /** The first index that is a nested sub-measure rather than a partition row. */
    const val FIRST_NESTED = 17

    // ── nested sub-measures (INSIDE the sections above) ───────────────────────
    /** `getTypeOfExpression(arg)` alone, inside [L_ARGTYPE]. */
    const val N_GET_TYPE_OF_EXPR = 17
    /** The three `getNarrowedTypeForReference` sites inside [L_ARGTYPE]. */
    const val N_NARROW = 18
    /** The M3.4 refinement gate's two `checkTypeRelatedTo` calls inside [L_ARGTYPE]. */
    const val N_ARGTYPE_REL = 19
    /** `literalTypeOfExpression`/`propTypeContainsLiteral` inside [L_ARGTYPE]. */
    const val N_LITERAL = 20
    /** The `checkTypeRelatedTo(argType, paramType, assignableRelation)` gate itself. */
    const val N_REL_CALL = 21
    /** `isSimpleCheckableType(paramType)` — the [L_NOTSIMPLE] gate. */
    const val N_ISSIMPLE = 22
    /**
     * The whole `for` loop as ONE span, for the same differential the
     * per-boundary calibration uses. Undercounts by whatever `return`s inside
     * the loop (two sites, both in [L_OBJLIT]); `calls[N_LOOP]` versus
     * [invocations] makes that visible.
     */
    const val N_LOOP = 23

    // -- the three narrowing sites, split (round 735) -------------------------
    /** B469 site: a UNION-typed Identifier/PropertyAccess argument. */
    const val N_NARROW_UNION = 24
    /** Round-441 site: a `never` parameter with a non-union argument. */
    const val N_NARROW_NEVER = 25
    /** M3.4 site: Interface/unknown/string/number argument, non-`never` parameter. */
    const val N_NARROW_M34 = 26

    /**
     * The subset of ALL THREE narrowing sites whose walk returned the input
     * type UNCHANGED — provably wasted work, and therefore the upper bound on
     * any pre-test that could skip the walk.
     */
    const val N_NARROW_IDENTITY = 27

    // -- (AUDIT.2) round 759: [L_ARGTYPE] split by the iteration's EXIT CLASS ---
    /**
     * [L_ARGTYPE] charged to the iterations that went on to REACH
     * [L_RELATION] — the 27% whose argument type is actually consumed by an
     * assignability check.
     */
    const val N_ARGTYPE_RELATING = 28

    /**
     * [L_ARGTYPE] charged to the iterations that left the loop body BEFORE
     * [L_RELATION]. This is the population `docs/perf/argument-check-attribution.md`
     * § 3 described as "yet all 37,379 pay for the full `argType` computation" —
     * a FREQUENCY (73% of iterations) that had never been priced.
     *
     * The two rows PARTITION [L_ARGTYPE] exactly, in both nanos and calls
     * (`ArgSectionProbeTest` pins it), so this is a measurement and not a
     * sample.
     */
    const val N_ARGTYPE_NONRELATING = 29

    /**
     * [N_GET_TYPE_OF_EXPR] and [N_NARROW], split by the same exit class — the
     * two terms that make up ~80% of the argType row. Without them the
     * difference between the classes is a RESIDUAL, and § 0's own history is
     * what a named residual costs.
     */
    const val N_GTOE_RELATING = 30
    const val N_GTOE_NONRELATING = 31
    const val N_NARROW_RELATING = 32
    const val N_NARROW_NONRELATING = 33

    // -- (CALL.6) round 797: the rest of the [L_ARGTYPE] row ------------------
    /**
     * The whole narrowing-ARM CHAIN (the `if (union) … else if (never) … else
     * if (enum) … else if (iface/str/num) … else ctxApplied`), which contains
     * [N_NARROW], [N_ARGTYPE_REL] and [N_GATE_REL].
     *
     * With [N_GET_TYPE_OF_EXPR] and [N_LITERAL] it closes [L_ARGTYPE] to a
     * SMALL named residue (the contextual install, `argIsNarrowableRef`,
     * `voidIifeArgType`, `stripNullishForNonNullArg`, the `finally`) — without
     * it the row's biggest term was a residual, and § 0's history is what a
     * named residual costs.
     */
    const val N_ARM_CHAIN = 34

    /** The (CALL.5)(b) pre-gate's own `checkTypeRelatedTo`, both arms. */
    const val N_GATE_REL = 35

    /** The wrapper's own transition. Probe-only; absent in production. */
    const val ENTRY = 36

    /** The FIRST empty boundary span of an invocation — not steady state. */
    const val OVERHEAD_FIRST = 37

    /** In-situ steady-state empty boundaries; a pessimistic upper bound (round 734). */
    const val OVERHEAD = 38

    const val N = 39

    val names: Array<String> = arrayOf(
        "prologue walkers (10, generic-gated)",
        "tryInferSingleTypeParamFromArgs",
        "B199/B204/B219 single-TP walkers",
        "loop: gates + getTypeOfSymbol(param)",
        "loop: argType computation",
        "loop: foreign-TP + rest + weak target",
        "loop: tryEmitWeakTypeAssignment",
        "loop: drill/array/objlit/union walkers",
        "loop: object-literal vs Object (353 ln)",
        "loop: nullish-argument blocks",
        "loop: object-literal vs TypeParam",
        "loop: paramType is TypeParam",
        "loop: arg-kind + class/index-sig block",
        "loop: !isSimpleCheckableType (196 ln)",
        "loop: optional-param / undefined gates",
        "loop: checkTypeRelatedTo + TS2345 emit",
        "post-loop rest-args check",
        "  of which getTypeOfExpression(arg)",
        "  of which getNarrowedTypeForReference",
        "  of which argType-gate checkTypeRelatedTo",
        "  of which literalTypeOfExpression",
        "  of which the TS2345 checkTypeRelatedTo",
        "  of which isSimpleCheckableType(param)",
        "  of which the whole loop as ONE span",
        "    narrow site B469 (union arg)",
        "    narrow site round-441 (never param)",
        "    narrow site M3.4 (iface/str/num arg)",
        "    narrow calls returning the INPUT type",
        "    argType of args REACHING the relation",
        "    argType of args exiting BEFORE it",
        "      - its getTypeOfExpression, REACHING",
        "      - its getTypeOfExpression, exiting",
        "      - its narrowing walks, REACHING",
        "      - its narrowing walks, exiting",
        "  of which the narrowing ARM CHAIN",
        "    of which the (CALL.5) pre-gate relation",
        "  wrapper transition (probe-only)",
        "  probe boundary, first of the invocation",
        "  probe boundary (in situ, steady state)",
    )

    /**
     * [COARSE]'s active boundaries. The three anchors still PARTITION the
     * function (pre-loop / loop / post-loop), so the partition total stays
     * comparable with [ON]'s; every other boundary is skipped before its
     * timestamp read.
     */
    val coarseAnchor: BooleanArray = BooleanArray(N).also {
        it[PRO] = true; it[L_PARAM] = true; it[POST] = true; it[ENTRY] = true
    }

    var nanos: LongArray = LongArray(N)
    var calls: LongArray = LongArray(N)

    /** Invocations of the instrumented function (nested ones excluded). */
    var invocations: Long = 0

    /** Loop iterations entered — the population most sections are measured over. */
    var iterations: Long = 0

    /**
     * Cost distribution of the narrowing walks: `[<10 us, <100 us, <1 ms, >=1 ms]`.
     * A mean of 59 us over 9,615 walks is compatible with two very different
     * shapes — a uniformly expensive population (which a skip pre-test would
     * fix) and a handful of monsters (which it would not) — so the buckets
     * decide before anything is designed.
     */
    var narrowBucketCalls: LongArray = LongArray(4)
    var narrowBucketNanos: LongArray = LongArray(4)

    /** The running section and its start timestamp; `-1` = none open. */
    var cur: Int = -1
    var curT: Long = 0
    var depth: Int = 0

    /**
     * (AUDIT.2): the [L_ARGTYPE] span of the CURRENT loop iteration, held until
     * the iteration's exit class is known; `-1` = nothing pending.
     *
     * The exit class cannot be decided where `argType` is computed — it is
     * settled up to eleven sections later — so the span is parked here and
     * charged when the iteration either opens [L_RELATION] (RELATING) or starts
     * the next iteration / ends the invocation without having done so
     * (NON-RELATING). Every iteration that opens [L_ARGTYPE] flushes exactly
     * once, which is what makes the two rows a partition rather than a sample.
     */
    var pendingArgType: Long = -1L

    /** The same iteration's [N_GET_TYPE_OF_EXPR] span, parked with it. */
    var pendingGtoe: Long = 0L

    /** The same iteration's narrowing time and walk count, parked with it. */
    var pendingNarrow: Long = 0L
    var pendingNarrowCalls: Long = 0L

    // ── (CALL.5) round 796: the EXIT CENSUS ───────────────────────────────────
    //
    // Round 789's instrument, in the shape this function needs. [leftIn] already
    // derives an exit profile by DIFFERENCING adjacent rows' `calls`, which is
    // sound only while every iteration passes through every boundary in order —
    // it cannot see the two `return`s inside the loop, it charges the prologue's
    // early returns to nothing, and it can say WHERE an iteration left but not
    // WHAT that iteration had already paid for. The census below answers both,
    // and it adds NO boundary: every count is taken at a boundary the partition
    // was already crossing, from the row that is already open.
    //
    // That property is load-bearing (round 793): a before/after row comparison
    // stays valid because the ON run's boundary COUNT is unchanged by it.

    /** Loop ITERATIONS that left the loop body from each row. */
    var exitRow: LongArray = LongArray(N)

    /** INVOCATIONS that returned (or fell out of the post-loop) from each row. */
    var exitInvRow: LongArray = LongArray(N)

    /** [L_ARGTYPE] nanos charged to the row the paying iteration LEFT from. */
    var exitArgTypeNanos: LongArray = LongArray(N)

    /** [N_NARROW] nanos and walks, charged the same way. */
    var exitNarrowNanos: LongArray = LongArray(N)
    var exitNarrowCalls: LongArray = LongArray(N)

    /**
     * The census's own copies of the parked spans. They must NOT be the
     * `pending*` fields: [flushPending] clears those the moment an iteration
     * reaches [L_RELATION], which is several boundaries before it exits.
     */
    var censusArgType: Long = 0L
    var censusNarrow: Long = 0L
    var censusNarrowCalls: Long = 0L

    /** True between [iteration] and the boundary that closes that iteration. */
    var iterOpen: Boolean = false

    // ── (CALL.6) round 797: the level-S sub-partition BY ARGUMENT KIND ────────
    //
    // Round 796's exit census localised 69% of the argument-typing time and 81%
    // of the narrowing onto ONE `continue` (the `!isSimpleCheckableType` block)
    // and hypothesised — round 759's sentence, never measured — that those
    // iterations are "arrows and callbacks typed under an installed contextual
    // type". A row cannot answer that: the exit is a property of the PARAMETER
    // while the cost is a property of the ARGUMENT, so the population has to be
    // classified by what the argument IS.
    //
    // Like the exit census this adds NO boundary: the classification is a
    // `when` over the argument node taken INSIDE the already-open [L_ARGTYPE]
    // row, and every nanosecond it attributes is a span the partition was
    // already timing. The five totals are printed as partition checks.

    /** The iteration never reached [L_ARGTYPE] (arity / spread / `any` gates). */
    const val K_NONE = 0
    const val K_IDENT = 1
    const val K_KEYWORD = 2
    const val K_PROP_ACCESS = 3
    const val K_ELEM_ACCESS = 4
    const val K_CALL = 5
    const val K_ARROW = 6
    const val K_FN_EXPR = 7
    const val K_OBJ_LIT = 8
    const val K_ARRAY_LIT = 9
    const val K_LITERAL = 10
    const val K_OPERATOR = 11
    const val K_CAST_LIKE = 12
    const val K_OTHER = 13
    const val KINDS = 14

    val kindNames: Array<String> = arrayOf(
        "(never reached argType)",
        "Identifier",
        "true/false/null/undefined/this",
        "PropertyAccess",
        "ElementAccess",
        "Call / New",
        "ArrowFunction",
        "FunctionExpression",
        "ObjectLiteral",
        "ArrayLiteral",
        "literal (string/number/template)",
        "operator (binary/cond/unary/…)",
        "as / ! / (…) / satisfies",
        "other",
    )

    /** Loop iterations whose argument was of each kind (i.e. reached [L_ARGTYPE]). */
    var kindIters: LongArray = LongArray(KINDS)

    /** …of which installed a CONTEXTUAL type for the argument (`useCtx`). */
    var kindCtx: LongArray = LongArray(KINDS)

    /** The [L_ARGTYPE] row, [N_GET_TYPE_OF_EXPR] and [N_NARROW], split by kind. */
    var kindArgType: LongArray = LongArray(KINDS)
    var kindGtoe: LongArray = LongArray(KINDS)
    var kindNarrow: LongArray = LongArray(KINDS)
    var kindNarrowCalls: LongArray = LongArray(KINDS)

    /** Kind × EXIT row: iterations, and the argType nanos they had paid. */
    var kindExitIters: LongArray = LongArray(KINDS * N)
    var kindExitArgType: LongArray = LongArray(KINDS * N)

    /** The kind of the argument the running iteration is checking. */
    var curArgKind: Int = K_NONE

    fun reset() {
        nanos = LongArray(N)
        calls = LongArray(N)
        invocations = 0
        iterations = 0
        narrowBucketCalls = LongArray(4)
        narrowBucketNanos = LongArray(4)
        cur = -1
        curT = 0
        depth = 0
        exitRow = LongArray(N)
        exitInvRow = LongArray(N)
        exitArgTypeNanos = LongArray(N)
        exitNarrowNanos = LongArray(N)
        exitNarrowCalls = LongArray(N)
        iterOpen = false
        kindIters = LongArray(KINDS)
        kindCtx = LongArray(KINDS)
        kindArgType = LongArray(KINDS)
        kindGtoe = LongArray(KINDS)
        kindNarrow = LongArray(KINDS)
        kindNarrowCalls = LongArray(KINDS)
        kindExitIters = LongArray(KINDS * N)
        kindExitArgType = LongArray(KINDS * N)
        curArgKind = K_NONE
        clearPending()
    }

    /**
     * (CALL.6) record the kind of the argument whose type is about to be
     * computed. Called from inside the already-open [L_ARGTYPE] row, so it adds
     * no boundary; the classification itself is a `when` over the node.
     */
    fun noteArgKind(kind: Int, ctx: Boolean) {
        curArgKind = kind
        kindIters[kind]++
        if (ctx) kindCtx[kind]++
    }

    /**
     * (CALL.5) close the running iteration's census against [cur] — the row it
     * is leaving from. Called only from a boundary the partition already had.
     */
    fun closeIterCensus() {
        exitRow[cur]++
        exitArgTypeNanos[cur] += censusArgType
        exitNarrowNanos[cur] += censusNarrow
        exitNarrowCalls[cur] += censusNarrowCalls
        // (CALL.6) the same close, crossed with the ARGUMENT KIND — this is the
        // cell that answers "what is the 39% that leaves at L_NOTSIMPLE".
        kindExitIters[curArgKind * N + cur]++
        kindExitArgType[curArgKind * N + cur] += censusArgType
        curArgKind = K_NONE
        censusArgType = 0L
        censusNarrow = 0L
        censusNarrowCalls = 0L
        iterOpen = false
    }

    /** Drop whatever the current iteration has parked. */
    fun clearPending() {
        pendingArgType = -1L
        pendingGtoe = 0L
        pendingNarrow = 0L
        pendingNarrowCalls = 0L
    }

    /**
     * Charge the parked iteration to its now-known exit class. [off] is `0` for
     * an iteration that reached [L_RELATION] and `1` for one that did not, so
     * the three pairs of rows stay index-adjacent and the flush is branch-free.
     */
    fun flushPending(off: Int) {
        nanos[N_ARGTYPE_RELATING + off] += pendingArgType
        calls[N_ARGTYPE_RELATING + off]++
        nanos[N_GTOE_RELATING + off] += pendingGtoe
        calls[N_GTOE_RELATING + off]++
        nanos[N_NARROW_RELATING + off] += pendingNarrow
        calls[N_NARROW_RELATING + off] += pendingNarrowCalls
        clearPending()
    }

    // The entry points are `inline` so a production call is a static read plus
    // a not-taken branch rather than a call, matching [CallSections].

    /** Open the partition for one invocation, starting at [ENTRY]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun begin() {
        if (mode == OFF) return
        depth++
        if (depth != 1) return
        invocations++
        pendingArgType = -1L
        cur = ENTRY
        curT = PassTiming.nowNanos()
    }

    /** Close the running section and start [sec]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun at(sec: Int) {
        if (mode == OFF || depth != 1) return
        if (mode == COARSE && !coarseAnchor[sec]) return
        val now = PassTiming.nowNanos()
        val d = now - curT
        nanos[cur] += d
        calls[cur]++
        // (AUDIT.2): park the argType span, or charge a parked one to its now-known
        // exit class. `at(L_PARAM)` opens the NEXT iteration, so a span still parked
        // there belongs to an iteration that left before the relation.
        if (mode == ON) {
            if (cur == L_ARGTYPE) {
                pendingArgType = d
                censusArgType = d
                kindArgType[curArgKind] += d
            } else if (pendingArgType >= 0L && (sec == L_RELATION || sec == L_PARAM)) {
                flushPending(if (sec == L_RELATION) 0 else 1)
            }
            // (CALL.5) `at(L_PARAM)` opens the NEXT iteration and `at(POST)` follows
            // the last one, so both close whatever iteration is still running —
            // against [cur], which has not been reassigned yet.
            if (iterOpen && (sec == L_PARAM || sec == POST)) closeIterCensus()
        }
        cur = sec
        curT = now
    }

    /** Count one loop iteration (free of any timestamp). */
    @Suppress("NOTHING_TO_INLINE")
    inline fun iteration() {
        if (mode == OFF || depth != 1) return
        iterations++
        if (mode == ON) iterOpen = true
    }

    /** Close whatever section is still open (the invocation may have returned). */
    @Suppress("NOTHING_TO_INLINE")
    inline fun end() {
        if (mode == OFF) return
        if (depth == 1) {
            if (cur >= 0) {
                val d = PassTiming.nowNanos() - curT
                nanos[cur] += d
                calls[cur]++
                // Only reachable if the invocation left INSIDE the argType block
                // (it cannot today — the block is an expression with a `finally`);
                // charging it keeps the partition exact if that ever changes.
                if (mode == ON && cur == L_ARGTYPE) {
                    pendingArgType = d
                    censusArgType = d
                    kindArgType[curArgKind] += d
                }
                // (CALL.5) the INVOCATION census: `cur` is the row the call
                // returned from — a prologue `return`, one of the two `return`s
                // inside the loop, or POST for a normal completion.
                if (mode == ON) {
                    exitInvRow[cur]++
                    if (iterOpen) closeIterCensus()
                }
                cur = -1
            }
            // The last iteration never sees another `at(L_PARAM)`, and the two
            // `return`s inside L_OBJLIT skip the loop's remaining boundaries.
            if (mode == ON && pendingArgType >= 0L) flushPending(1)
        }
        depth--
    }

    /** Start a NESTED sub-measure, or 0 when off. Never active under [COARSE]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun t(): Long = if (mode == ON) PassTiming.nowNanos() else 0L

    /** Close a NESTED sub-measure opened at [t0]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun close(sec: Int, t0: Long) {
        if (mode != ON) return
        val d = PassTiming.nowNanos() - t0
        nanos[sec] += d
        calls[sec]++
        // (AUDIT.2): park it with the iteration's argType span — the exit class
        // is not known for another eleven sections.
        if (sec == N_GET_TYPE_OF_EXPR) {
            pendingGtoe += d
            kindGtoe[curArgKind] += d
        }
    }

    /**
     * Close one narrowing walk: charge it to its own site, to the combined
     * [N_NARROW] row, to a cost bucket, and — when [changed] is false — to
     * [N_NARROW_IDENTITY], the walk that could in principle have been skipped.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun closeNarrow(site: Int, t0: Long, changed: Boolean) {
        if (mode != ON) return
        val d = PassTiming.nowNanos() - t0
        nanos[site] += d; calls[site]++
        nanos[N_NARROW] += d; calls[N_NARROW]++
        pendingNarrow += d; pendingNarrowCalls++
        censusNarrow += d; censusNarrowCalls++
        kindNarrow[curArgKind] += d; kindNarrowCalls[curArgKind]++
        if (!changed) { nanos[N_NARROW_IDENTITY] += d; calls[N_NARROW_IDENTITY]++ }
        val b = if (d < 10_000L) 0 else if (d < 100_000L) 1 else if (d < 1_000_000L) 2 else 3
        narrowBucketCalls[b]++; narrowBucketNanos[b] += d
    }

    fun report(): String = buildString {
        appendLine("== (CALL.2) intra-function attribution: checkArgumentsAgainstSignature ==")
        appendLine("mode: ${if (mode == COARSE) "COARSE (anchors only)" else "ON"}")
        appendLine("invocations: $invocations   loop iterations: $iterations")
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
        var boundaries = 0L
        for (s in 0 until FIRST_NESTED) {
            partition += nanos[s] - ovh * calls[s]; raw += nanos[s]; boundaries += calls[s]
        }
        appendLine(
            "partition total: ${partition / 1_000_000} ms net, ${raw / 1_000_000} ms raw " +
                "over $boundaries boundaries"
        )
        appendLine("-- sections (disjoint, source order; ms net of probe overhead) --")
        for (s in 0 until N) {
            val c = calls[s]
            if (s == FIRST_NESTED) appendLine("-- nested sub-measures (INSIDE the sections above) --")
            if (c == 0L) continue
            val ns = nanos[s] - ovh * c
            appendLine(
                "  ${names[s].padEnd(42)} ${(ns / 1_000_000).toString().padStart(5)} ms net " +
                    "(${(nanos[s] / 1_000_000).toString().padStart(5)} raw) reached ${
                        c.toString().padStart(7)
                    } = ${if (c > 0) ns / c else 0} ns each" +
                    if (s in L_PARAM until POST) ", leftIn=${leftIn(s)}" else ""
            )
        }
        val relN = nanos[N_ARGTYPE_RELATING]
        val nonN = nanos[N_ARGTYPE_NONRELATING]
        val relC = calls[N_ARGTYPE_RELATING]
        val nonC = calls[N_ARGTYPE_NONRELATING]
        if (relC + nonC > 0) {
            appendLine("-- (AUDIT.2) argType by EXIT CLASS (raw; partitions the argType row) --")
            val tot = relN + nonN
            appendLine(
                "  reaches the relation  ${(relN / 1_000_000).toString().padStart(5)} ms " +
                    "(${if (tot > 0) relN * 100 / tot else 0}% of argType) over " +
                    "${relC.toString().padStart(7)} args = ${if (relC > 0) relN / relC else 0} ns each"
            )
            appendLine(
                "  exits before it       ${(nonN / 1_000_000).toString().padStart(5)} ms " +
                    "(${if (tot > 0) nonN * 100 / tot else 0}% of argType) over " +
                    "${nonC.toString().padStart(7)} args = ${if (nonC > 0) nonN / nonC else 0} ns each"
            )
            appendLine(
                "  partition check: rel+non = ${relN + nonN} ns / ${relC + nonC} calls" +
                    " vs argType row ${nanos[L_ARGTYPE]} ns / ${calls[L_ARGTYPE]} calls" +
                    if (relN + nonN == nanos[L_ARGTYPE] && relC + nonC == calls[L_ARGTYPE])
                        "  EXACT" else "  *** MISMATCH ***"
            )
            // The two terms that make the classes differ — measured, so the gap
            // between them is not a residual with a name on it.
            for ((label, base) in listOf(
                "getTypeOfExpression" to N_GTOE_RELATING,
                "narrowing walks    " to N_NARROW_RELATING,
            )) {
                appendLine(
                    "    $label  reaching ${(nanos[base] / 1_000_000).toString().padStart(4)} ms" +
                        " /${calls[base].toString().padStart(7)} = " +
                        "${if (calls[base] > 0) nanos[base] / calls[base] else 0} ns" +
                        "   exiting ${(nanos[base + 1] / 1_000_000).toString().padStart(4)} ms" +
                        " /${calls[base + 1].toString().padStart(7)} = " +
                        "${if (calls[base + 1] > 0) nanos[base + 1] / calls[base + 1] else 0} ns"
                )
            }
        }
        var censusIters = 0L
        for (s in 0 until N) censusIters += exitRow[s]
        if (censusIters > 0L) {
            appendLine("-- (CALL.5) EXIT CENSUS — where each ITERATION left, and what it had paid --")
            appendLine("   (argType/narrow are RAW nanos, charged to the row the iteration LEFT from)")
            for (s in 0 until FIRST_NESTED) {
                if (exitRow[s] == 0L) continue
                appendLine(
                    "  ${names[s].padEnd(42)} ${exitRow[s].toString().padStart(7)} left here" +
                        ", argType ${(exitArgTypeNanos[s] / 1_000_000).toString().padStart(4)} ms" +
                        ", narrow ${(exitNarrowNanos[s] / 1_000_000).toString().padStart(4)} ms" +
                        " /${exitNarrowCalls[s].toString().padStart(6)} walks"
                )
            }
            var ceArg = 0L
            var ceNar = 0L
            var ceNarC = 0L
            for (s in 0 until N) { ceArg += exitArgTypeNanos[s]; ceNar += exitNarrowNanos[s]; ceNarC += exitNarrowCalls[s] }
            appendLine(
                "  census check: iterations $censusIters vs $iterations" +
                    "; argType $ceArg ns vs row ${nanos[L_ARGTYPE]}" +
                    "; narrow $ceNar ns / $ceNarC vs row ${nanos[N_NARROW]} / ${calls[N_NARROW]}" +
                    if (censusIters == iterations && ceArg == nanos[L_ARGTYPE] &&
                        ceNar == nanos[N_NARROW] && ceNarC == calls[N_NARROW]
                    ) "  EXACT" else "  *** MISMATCH ***"
            )
            appendLine("-- (CALL.5) EXIT CENSUS — where each INVOCATION returned --")
            var censusInv = 0L
            for (s in 0 until N) censusInv += exitInvRow[s]
            for (s in 0 until FIRST_NESTED) {
                if (exitInvRow[s] == 0L) continue
                appendLine("  ${names[s].padEnd(42)} ${exitInvRow[s].toString().padStart(7)} returned here")
            }
            appendLine(
                "  census check: invocations $censusInv vs $invocations" +
                    if (censusInv == invocations) "  EXACT" else "  *** MISMATCH ***"
            )
        }
        var kindTotal = 0L
        for (k in 0 until KINDS) kindTotal += kindIters[k]
        if (kindTotal > 0L) {
            appendLine("-- (CALL.6) LEVEL-S: the argType row split by ARGUMENT KIND (raw ns) --")
            appendLine(
                "   ${"kind".padEnd(34)} ${"iters".padStart(7)} ${"ctx".padStart(6)}" +
                    " ${"argType".padStart(8)} ${"gToExpr".padStart(8)} ${"narrow".padStart(8)}" +
                    " ${"walks".padStart(6)} ${"ns/iter".padStart(9)}"
            )
            for (k in 0 until KINDS) {
                if (kindIters[k] == 0L && k != K_NONE) continue
                appendLine(
                    "   ${kindNames[k].padEnd(34)} ${kindIters[k].toString().padStart(7)}" +
                        " ${kindCtx[k].toString().padStart(6)}" +
                        " ${(kindArgType[k] / 1_000_000).toString().padStart(6)} ms" +
                        " ${(kindGtoe[k] / 1_000_000).toString().padStart(6)} ms" +
                        " ${(kindNarrow[k] / 1_000_000).toString().padStart(6)} ms" +
                        " ${kindNarrowCalls[k].toString().padStart(6)}" +
                        " ${
                            (if (kindIters[k] > 0) kindArgType[k] / kindIters[k] else 0)
                                .toString().padStart(9)
                        }"
                )
            }
            var kIters = 0L; var kArg = 0L; var kGtoe = 0L; var kNar = 0L; var kNarC = 0L
            for (k in 0 until KINDS) {
                kIters += kindIters[k]; kArg += kindArgType[k]; kGtoe += kindGtoe[k]
                kNar += kindNarrow[k]; kNarC += kindNarrowCalls[k]
            }
            appendLine(
                "  partition check: iters $kIters vs argType row ${calls[L_ARGTYPE]}" +
                    "; argType $kArg vs ${nanos[L_ARGTYPE]}" +
                    "; gToExpr $kGtoe vs ${nanos[N_GET_TYPE_OF_EXPR]}" +
                    "; narrow $kNar/$kNarC vs ${nanos[N_NARROW]}/${calls[N_NARROW]}" +
                    if (kIters == calls[L_ARGTYPE] && kArg == nanos[L_ARGTYPE] &&
                        kGtoe == nanos[N_GET_TYPE_OF_EXPR] && kNar == nanos[N_NARROW] &&
                        kNarC == calls[N_NARROW]
                    ) "  EXACT" else "  *** MISMATCH ***"
            )
            appendLine("-- (CALL.6) LEVEL-S: KIND x EXIT ROW (iterations / argType ms) --")
            for (k in 0 until KINDS) {
                var any = false
                for (s in 0 until FIRST_NESTED) if (kindExitIters[k * N + s] != 0L) any = true
                if (!any) continue
                val sb = StringBuilder()
                for (s in 0 until FIRST_NESTED) {
                    val it0 = kindExitIters[k * N + s]
                    if (it0 == 0L) continue
                    sb.append("  [").append(names[s].trim().removePrefix("loop: ")).append("] ")
                        .append(it0).append(" / ")
                        .append(kindExitArgType[k * N + s] / 1_000_000).append(" ms")
                }
                appendLine("   ${kindNames[k].padEnd(34)}$sb")
            }
            var kx = 0L
            for (i in kindExitIters.indices) kx += kindExitIters[i]
            appendLine(
                "  partition check: kind x exit iterations $kx vs $iterations" +
                    if (kx == iterations) "  EXACT" else "  *** MISMATCH ***"
            )
        }
        appendLine("-- narrowing-walk cost distribution (all three sites) --")
        val labels = arrayOf("< 10 us", "10-100 us", "0.1-1 ms", ">= 1 ms")
        for (b in 0 until 4) {
            appendLine(
                "  ${labels[b].padEnd(12)} ${narrowBucketCalls[b].toString().padStart(7)} walks, " +
                    "${(narrowBucketNanos[b] / 1_000_000).toString().padStart(5)} ms"
            )
        }
    }

    /**
     * Loop iterations that left section [sec] other than by falling through to
     * the next one — i.e. `continue`d, `break`ed or returned inside it. The
     * loop sections are strictly sequential, so the drop to the next section
     * is exactly that count; [L_RELATION] is the last, so everything reaching
     * it leaves inside it.
     */
    fun leftIn(sec: Int): Long =
        if (sec in L_PARAM until L_RELATION) calls[sec] - calls[sec + 1] else calls[sec]

    /** Machine-readable dump: one line per section. */
    fun csv(): String = buildString {
        appendLine("section,reached,nanos")
        for (s in 0 until N) {
            if (calls[s] == 0L) continue
            appendLine("\"${names[s].trim()}\",${calls[s]},${nanos[s]}")
        }
    }
}

/**
 * (CALL.5)(b) round 796 — the ALREADY-RELATES pre-gate on the argument check's
 * two unconditional narrowing arms, and its own equivalence instrument.
 *
 * **What it does.** In `checkArgumentsAgainstSignatureCore` the argument type is
 * flow-narrowed at three sites. Round 764 gave the ENUM site a "second chance"
 * shape — *walk only when the raw type does NOT already satisfy the parameter*,
 * because a narrow can then only turn a rejection into an acceptance and never
 * the reverse — and measured that skipping the already-relating calls removed
 * 3,406 walks. It then declined to generalise, in a comment that names its
 * creditor: *"Deliberately enum-ONLY: the Interface/`unknown`/`string`/`number`
 * arms below are corpus-pinned"*. Round 796 re-tests that debt (the round-783
 * rule) on the two arms that hold the mass — measured at HEAD, **B469 116 ms /
 * 2,455 walks and M3.4 300 ms / 7,368 walks, of a 478 ms narrowing total that is
 * 33% of the whole function**.
 *
 * **Why the gate is acceptance-preserving.** It fires only when the UNNARROWED
 * type already relates to the parameter, i.e. only where the assignability
 * emission at the bottom of the loop was going to stay silent either way. What
 * it can still change is a CONSUMER other than that relation — the weak-type
 * rule's shared-property test, the `!isSimpleCheckableType` block's shape
 * classification, a display. That is not arguable from the source, so it is
 * MEASURED: [CENSUS] keeps the old behaviour and counts, per arm, how many
 * refusals would have SUBSTITUTED a different type. A refusal that would not
 * have substituted cannot change anything at all, by construction.
 *
 * **The positive control is free** (round 790): the same counter over the KEPT
 * complement — the walks the gate never refuses — must be non-zero, and is
 * ([keptChanged]). No deliberately bogus flag is needed to prove the instrument
 * is alive.
 */
object ArgNarrowGate {

    /** Pre-round-796 behaviour: both arms walk unconditionally, no gate evaluated. */
    const val OFF = 0

    /**
     * Evaluate the gate, RECORD its verdict, and then behave exactly as [OFF]
     * did — so a `--verifyArgNarrowGate` run reproduces the pre-change binary
     * by construction and is a legitimate grid baseline.
     */
    const val CENSUS = 1

    /** Act on the gate. The production setting since round 796. */
    const val ON = 2

    var mode: Int = ON

    /** The B469 arm: a UNION-typed narrowable reference argument. */
    const val UNION = 0

    /** The M3.4 arm: Interface / `unknown` / `string` / `number` / enum-flavored. */
    const val M34 = 1

    val armNames: Array<String> = arrayOf("B469 union arg", "M3.4 iface/str/num arg")

    /** Arm reached with the gate live (i.e. a walk was in prospect). */
    var reached: LongArray = LongArray(2)

    /** …of which the gate refused (the raw type already relates to the parameter). */
    var refused: LongArray = LongArray(2)

    /**
     * …of which the walk WOULD have substituted a different type. Only [CENSUS]
     * can fill this: under [ON] the walk does not happen. **This is the number
     * the change stands or falls on** — it is exactly the set of argument types
     * that differ between the gated and ungated binaries.
     */
    var refusedChanged: LongArray = LongArray(2)

    /** The FREE positive control: substitutions in the complement the gate keeps. */
    var keptChanged: LongArray = LongArray(2)

    fun reset() {
        reached = LongArray(2); refused = LongArray(2)
        refusedChanged = LongArray(2); keptChanged = LongArray(2)
    }

    /** Record one visit to [arm]. [changed] is unknown (false) when no walk ran. */
    fun note(arm: Int, gateRefuses: Boolean, changed: Boolean) {
        reached[arm]++
        if (gateRefuses) {
            refused[arm]++
            if (changed) refusedChanged[arm]++
        } else if (changed) keptChanged[arm]++
    }

    fun report(): String = buildString {
        appendLine("== (CALL.5)(b) argument-narrowing already-relates gate ==")
        appendLine("mode: ${when (mode) { OFF -> "OFF"; CENSUS -> "CENSUS (old behaviour + verdict)"; else -> "ON" }}")
        for (a in 0 until 2) {
            if (reached[a] == 0L) continue
            appendLine(
                "  ${armNames[a].padEnd(24)} reached ${reached[a].toString().padStart(6)}" +
                    ", refused ${refused[a].toString().padStart(6)}" +
                    " (${refused[a] * 100 / reached[a]}%)" +
                    ", of those SUBSTITUTING ${refusedChanged[a]}" +
                    "   [kept-complement substitutions: ${keptChanged[a]}]"
            )
        }
        val rc = refusedChanged[0] + refusedChanged[1]
        val kc = keptChanged[0] + keptChanged[1]
        appendLine(
            "  verdict: $rc refusal(s) would have substituted" +
                (if (mode == CENSUS && kc == 0L) "  *** CONTROL DEAD — kept complement never substitutes ***"
                else if (mode == CENSUS) "  (control alive: $kc)" else "")
        )
    }
}

/**
 * (CALL.3)(a) round 736: the opt-in attribution INSIDE `narrowTypeFromFlow` —
 * the fifth in the 732→736 sequence, and the first whose target was measured
 * ABOVE the ±2% drift band before the round started (round 735: **394 of
 * 70,037 walks carry 1,485 ms = 47% of all flow narrowing**, at 2,354 ns per
 * flow-node arrival against a 372 ns all-walk mean).
 *
 * The item names two numbers that must exist before anything is designed, and
 * this object produces exactly those:
 *
 * 1. **Node ARRIVALS versus DISTINCT flow nodes per walk.** An arrival is one
 *    iteration of the fast-forward `while (true)` loop; a distinct node is a
 *    flow-node id first seen in this walk. `NarrowFlowMemo.served(id, depth)`
 *    only answers when `depth <= storedDepth`, so a revisit reached by a
 *    LONGER path misses and recomputes — [memoMissDepth] separates that miss
 *    (an entry EXISTS but was stored too shallow) from [memoMissAbsent] (never
 *    computed). If arrivals are much larger than distinct AND the misses are
 *    depth misses, the depth condition is the lever; if the misses are
 *    absences, it is not.
 * 2. **The per-arrival split.** Nested sub-measures around every leaf call the
 *    walk makes — each excludes the recursion by construction, because the
 *    recursive `narrowTypeFromFlow` call is a separate statement in every arm
 *    — so the rows are self time and sum toward the walk total.
 *
 * ## Why counters, not timestamps, inside the arrival loop
 *
 * Rounds 734/735 measured a timestamp read at 86–89 ns. The compile makes
 * ~8.5 M arrivals, so a single timestamp PAIR per arrival would add ~1.5 s to
 * a 3.2 s population — the probe would be the measurement. The two per-arrival
 * structures are therefore priced in **probe steps** ([NarrowProbe]), a
 * deterministic integer counter incremented inside the open-addressing loops
 * of `NarrowFlowMemo` and `NarrowSeen`. Steps are exactly what those data
 * structures cost, and the probe cannot inflate them.
 *
 * ## Calibration
 *
 * [COARSE] keeps only the [S_WALK] anchor (2 reads per depth-0 walk, ~140 k
 * for the whole compile = ~12 ms, negligible), so an ON-vs-COARSE pair divided
 * by the extra boundary count gives the per-read cost with no cold-start and
 * no safepoint artifact. This is the same differential rounds 734 and 735
 * landed on independently; the in-situ empty span has over-read by 3.6x and
 * 4.4x in consecutive rounds and is not used here at all.
 */
object NarrowSections {

    const val OFF = 0
    const val ON = 1

    /** Anchor only ([S_WALK]) — the calibration counterpart of [ON]. */
    const val COARSE = 2

    /**
     * (CALL.4): [ON] plus the [C_REFPATH] rows — the `getReferencePath` calls
     * inside `applyConditionNarrowing`. Separated because that call is an order
     * of magnitude more frequent than the `narrowBy*` leaves, so its boundary
     * pairs would inflate every other row; ON→DEEP is its own differential.
     */
    const val DEEP = 3

    /** Opt-in; [OFF] in production. Set by `--narrowSections{,Coarse,Deep}`. */
    var mode: Int = OFF

    /** True in the two timing modes — [COARSE] takes the anchor only. */
    val timing: Boolean get() = mode == ON || mode == DEEP

    // -- sections: nested sub-measures, NOT a partition (the function recurses)
    /** The outermost `narrowTypeFromFlow` call — the anchor, and the total. */
    const val S_WALK = 0
    /** The fast-forward `while (true)` loop, per INVOCATION (self, no recursion). */
    const val S_FF = 1
    /** `applyConditionNarrowing` at a `FlowCondition`. */
    const val S_COND = 2
    /** `getUnionType(branchTypes)` at a `FlowBranchLabel`. */
    const val S_UNION = 3
    /** `narrowByAssignmentRhs` at a narrowing `FlowAssignment`. */
    const val S_ASSIGN = 4
    /** `narrowByAssertCall` at a narrowing `FlowCall`. */
    const val S_ASSERT = 5
    /** `narrowBySwitchClause` at a `FlowSwitchClause`. */
    const val S_SWITCH = 6
    /** `outerFlowForCapturedName` at a `FlowStart` (B464 closure capture). */
    const val S_START = 7
    /** `memo.putIfDeeper` on a clean completion. */
    const val S_PUT = 8

    const val N = 9

    val names: Array<String> = arrayOf(
        "the whole walk (outermost entry)",
        "  fast-forward loop (per invocation)",
        "  applyConditionNarrowing",
        "  getUnionType at a branch label",
        "  narrowByAssignmentRhs",
        "  narrowByAssertCall",
        "  narrowBySwitchClause",
        "  outerFlowForCapturedName",
        "  memo.putIfDeeper",
    )

    var nanos: LongArray = LongArray(N)
    var calls: LongArray = LongArray(N)

    // -- (CALL.4): the split INSIDE applyConditionNarrowing --------------------
    // Nested sub-measures again, never a partition: the dispatcher recurses, and
    // every row below brackets a call that provably does NOT re-enter it (the
    // recursive arms — wrappers, `!`, `||`/`&&`/`??`, the alias inline — call it
    // as a separate statement, so no row contains a recursion).
    const val C_EQ = 0
    const val C_INSTOF = 1
    const val C_IN = 2
    const val C_CALLPRED = 3
    const val C_TRUTHY = 4
    const val C_DISCRIM = 5
    const val C_EXNULL = 6
    const val C_ISARRAY = 7
    const val C_ALIAS = 8
    const val C_UNION = 9
    const val C_REFPATH = 10
    const val NC = 11

    val cNames: Array<String> = arrayOf(
        "narrowByEquality",
        "narrowByInstanceOf",
        "narrowByInOperator",
        "narrowByCallPredicate",
        "narrowByTruthiness",
        "narrowByBooleanDiscriminantTruthiness",
        "narrowByExcludingNullUndefined",
        "narrowByArrayIsArray",
        "aliasedConditionInitializer",
        "getUnionType at ||/&&/??",
        "getReferencePath (DEEP only)",
    )

    /** Scratch for the outermost `applyConditionNarrowing` call in progress. */
    var cScratchNanos: LongArray = LongArray(NC)
    var cScratchCalls: LongArray = LongArray(NC)

    /**
     * The rows, split by whether the OUTERMOST call narrowed. This is the whole
     * point of (CALL.4): round 736 measured 33,307 narrowing calls at 21,708 ns
     * against 726,477 identity calls at 949 ns, and the question is what the
     * 21,708 ns is made of.
     */
    var cNanosNarrow: LongArray = LongArray(NC)
    var cCallsNarrow: LongArray = LongArray(NC)
    var cNanosIdent: LongArray = LongArray(NC)
    var cCallsIdent: LongArray = LongArray(NC)

    // -- which `when` arm each invocation took ---------------------------------
    const val A_WRAPPER = 0
    const val A_PREFIX = 1
    const val A_LOGICAL = 2
    const val A_EQUALITY = 3
    const val A_ASSIGN = 4
    const val A_INSTOF = 5
    const val A_IN = 6
    const val A_BIN_OTHER = 7
    const val A_CALL = 8
    const val A_IDENT = 9
    const val A_PROPACCESS = 10
    const val A_OTHER = 11
    const val NA = 12

    val armNames: Array<String> = arrayOf(
        "wrapper (paren/as/satisfies/nonnull)", "prefix !", "|| && ??",
        "=== !== == !=", "= (truthy assignment)", "instanceof", "in",
        "binary, other operator", "call", "identifier", "property access",
        "other expression kind",
    )

    /** Arm dispatches over ALL invocations, recursive ones included. */
    var armAll: LongArray = LongArray(NA)
    /** The same, folded only for outermost calls that NARROWED. */
    var armNarrow: LongArray = LongArray(NA)
    /** Scratch for the outermost call in progress. */
    var armScratch: LongArray = LongArray(NA)

    /** Invocations (recursive included) and the per-outermost-call fan-out. */
    var acnInvocations: Long = 0
    var acnInvNarrow: Long = 0
    var acnInvIdent: Long = 0
    var acnScratchInv: Long = 0
    var acnFanOutMax: Long = 0

    // -- flow-node kinds, for the arrival census -------------------------------
    const val K_START = 0
    const val K_UNREACHABLE = 1
    const val K_CONDITION = 2
    const val K_BRANCH = 3
    const val K_LOOP = 4
    const val K_ASSIGNMENT = 5
    const val K_CALL = 6
    const val K_SWITCH = 7
    const val K_ARRAY_MUTATION = 8
    const val NKINDS = 9

    val kindNames: Array<String> = arrayOf(
        "FlowStart", "FlowUnreachable", "FlowCondition", "FlowBranchLabel",
        "FlowLoopLabel", "FlowAssignment", "FlowCall", "FlowSwitchClause",
        "FlowArrayMutation",
    )

    /** Arrivals by flow-node kind, over ALL walks. */
    var arrivalsByKind: LongArray = LongArray(NKINDS)
    /** The same, restricted to walks that took >= 1 ms (the round-735 tail). */
    var hugeArrivalsByKind: LongArray = LongArray(NKINDS)
    /** Scratch for the walk in progress, folded into one of the two above. */
    var walkArrivalsByKind: LongArray = LongArray(NKINDS)

    /** Depth-0 walks, and how many of them landed in the >= 1 ms tail. */
    var walks: Long = 0
    var hugeWalks: Long = 0
    /** `narrowTypeFromFlow` invocations (recursive ones included). */
    var invocations: Long = 0
    var hugeInvocations: Long = 0
    /** Arrivals (fast-forward loop iterations) and DISTINCT flow-node ids. */
    var arrivals: Long = 0
    var distinct: Long = 0
    var hugeArrivals: Long = 0
    var hugeDistinct: Long = 0
    /** Max arrivals and max distinct over the tail — the mean hides the shape. */
    var hugeArrivalsMax: Long = 0
    var hugeDistinctMax: Long = 0

    /** Intra-walk memo outcomes, split by WHY a probe did not serve. */
    var memoServe: Long = 0
    var memoMissAbsent: Long = 0
    var memoMissDepth: Long = 0
    var hugeMemoServe: Long = 0
    var hugeMemoMissAbsent: Long = 0
    var hugeMemoMissDepth: Long = 0
    /** `seen.add` returned false — the path-cycle bail (a TRUNCATING exit). */
    var seenCycle: Long = 0
    var hugeSeenCycle: Long = 0
    /** Walks whose result was the declared type they started from. */
    var identityWalks: Long = 0
    var hugeIdentityWalks: Long = 0

    /** Scratch counters for the walk in progress. */
    var wArrivals: Long = 0
    var wDistinct: Long = 0
    var wInvocations: Long = 0
    var wMemoServe: Long = 0
    var wMemoMissAbsent: Long = 0
    var wMemoMissDepth: Long = 0
    var wSeenCycle: Long = 0

    /** Open-addressing probe steps in the two per-arrival structures. */
    var probeStepsMemo: Long = 0
    var probeStepsSeen: Long = 0
    var hugeProbeSteps: Long = 0

    /**
     * The memo outcome split BY FLOW-NODE KIND. This is what decides whether
     * relaxing the depth condition is worth anything: a `missTooShallow` at a
     * `FlowCondition` costs an `applyConditionNarrowing` plus the whole
     * antecedent recursion, while one at a pass-through node costs a pointer
     * hop. `[0]` = served, `[1]` = missAbsent, `[2]` = missTooShallow.
     */
    var memoOutcomeByKind: Array<LongArray> = Array(3) { LongArray(NKINDS) }
    var hugeMemoOutcomeByKind: Array<LongArray> = Array(3) { LongArray(NKINDS) }
    var walkMemoOutcomeByKind: Array<LongArray> = Array(3) { LongArray(NKINDS) }

    /**
     * The depth actually reached, against `NARROW_MAX_DEPTH` = 2000. The memo's
     * depth condition exists ONLY because a deeper entry has less depth budget
     * and might truncate where the stored computation did not; if the observed
     * maximum is two orders of magnitude below the limit, the condition is
     * rejecting serves for a truncation that cannot happen.
     */
    var maxDepth: Long = 0
    /** The three truncating exits, counted (round 735 measured trips at 0). */
    var truncDepth: Long = 0
    var truncBudget: Long = 0

    /**
     * `applyConditionNarrowing` calls that returned their INPUT type unchanged
     * — the condition said nothing about the walked reference. This is the
     * upper bound on any cheap "does this condition mention the name" pre-test.
     */
    var condCalls: Long = 0
    var condIdentity: Long = 0
    var condIdentityNanos: Long = 0

    fun reset() {
        nanos = LongArray(N); calls = LongArray(N)
        arrivalsByKind = LongArray(NKINDS)
        hugeArrivalsByKind = LongArray(NKINDS)
        walkArrivalsByKind = LongArray(NKINDS)
        walks = 0; hugeWalks = 0; invocations = 0; hugeInvocations = 0
        arrivals = 0; distinct = 0; hugeArrivals = 0; hugeDistinct = 0
        hugeArrivalsMax = 0; hugeDistinctMax = 0
        memoServe = 0; memoMissAbsent = 0; memoMissDepth = 0
        hugeMemoServe = 0; hugeMemoMissAbsent = 0; hugeMemoMissDepth = 0
        seenCycle = 0; hugeSeenCycle = 0
        identityWalks = 0; hugeIdentityWalks = 0
        probeStepsMemo = 0; probeStepsSeen = 0; hugeProbeSteps = 0
        memoOutcomeByKind = Array(3) { LongArray(NKINDS) }
        hugeMemoOutcomeByKind = Array(3) { LongArray(NKINDS) }
        walkMemoOutcomeByKind = Array(3) { LongArray(NKINDS) }
        maxDepth = 0; truncDepth = 0; truncBudget = 0
        condCalls = 0; condIdentity = 0; condIdentityNanos = 0
        cScratchNanos = LongArray(NC); cScratchCalls = LongArray(NC)
        cNanosNarrow = LongArray(NC); cCallsNarrow = LongArray(NC)
        cNanosIdent = LongArray(NC); cCallsIdent = LongArray(NC)
        armAll = LongArray(NA); armNarrow = LongArray(NA); armScratch = LongArray(NA)
        acnInvocations = 0; acnInvNarrow = 0; acnInvIdent = 0
        acnScratchInv = 0; acnFanOutMax = 0
        clearWalk()
    }

    fun clearWalk() {
        wArrivals = 0; wDistinct = 0; wInvocations = 0
        wMemoServe = 0; wMemoMissAbsent = 0; wMemoMissDepth = 0
        wSeenCycle = 0
        for (k in 0 until NKINDS) walkArrivalsByKind[k] = 0
        for (o in 0 until 3) for (k in 0 until NKINDS) walkMemoOutcomeByKind[o][k] = 0
    }

    /** Record a memo probe outcome ([o]: 0 served, 1 absent, 2 too shallow). */
    fun memoOutcome(o: Int) {
        walkMemoOutcomeByKind[o][NarrowProbe.curKind]++
    }

    /** Start of a depth-0 walk. Returns the anchor timestamp. */
    fun beginWalk(): Long {
        clearWalk()
        NarrowProbe.on = true
        NarrowProbe.steps = 0
        NarrowProbe.clearDistinct()
        return PassTiming.nowNanos()
    }

    /**
     * End of a depth-0 walk: fold the scratch into the ALL accumulators and,
     * when the walk took >= 1 ms, into the tail accumulators too. `>= 1 ms` is
     * round 735's own tail definition, so the two rounds' populations match.
     */
    fun endWalk(t0: Long, identity: Boolean) {
        val took = PassTiming.nowNanos() - t0
        NarrowProbe.on = false
        val steps = NarrowProbe.steps
        if (timing) { nanos[S_WALK] += took; calls[S_WALK]++ }
        walks++
        arrivals += wArrivals; distinct += wDistinct; invocations += wInvocations
        memoServe += wMemoServe
        memoMissAbsent += wMemoMissAbsent; memoMissDepth += wMemoMissDepth
        seenCycle += wSeenCycle
        if (identity) identityWalks++
        for (k in 0 until NKINDS) arrivalsByKind[k] += walkArrivalsByKind[k]
        for (o in 0 until 3) for (k in 0 until NKINDS) {
            memoOutcomeByKind[o][k] += walkMemoOutcomeByKind[o][k]
        }
        if (took >= 1_000_000L) {
            for (o in 0 until 3) for (k in 0 until NKINDS) {
                hugeMemoOutcomeByKind[o][k] += walkMemoOutcomeByKind[o][k]
            }
        }
        if (took >= 1_000_000L) {
            hugeWalks++
            hugeArrivals += wArrivals; hugeDistinct += wDistinct
            hugeInvocations += wInvocations
            hugeMemoServe += wMemoServe
            hugeMemoMissAbsent += wMemoMissAbsent; hugeMemoMissDepth += wMemoMissDepth
            hugeSeenCycle += wSeenCycle
            hugeProbeSteps += steps
            if (identity) hugeIdentityWalks++
            if (wArrivals > hugeArrivalsMax) hugeArrivalsMax = wArrivals
            if (wDistinct > hugeDistinctMax) hugeDistinctMax = wDistinct
            for (k in 0 until NKINDS) hugeArrivalsByKind[k] += walkArrivalsByKind[k]
        }
    }

    /** One arrival at flow node [id] of [kind]. Counter-only — never a timestamp. */
    fun arrival(id: Int, kind: Int, depth: Int) {
        wArrivals++
        walkArrivalsByKind[kind]++
        NarrowProbe.curKind = kind
        if (depth > maxDepth) maxDepth = depth.toLong()
        if (NarrowProbe.noteDistinct(id)) wDistinct++
    }

    /**
     * (CALL.4) Open the OUTERMOST `applyConditionNarrowing`. Clears the per-call
     * scratch, so anything an unbracketed entry point (the `FollowLoopEntry`
     * mirror, `narrowByAssertCall`) left behind is discarded rather than
     * mis-attributed to the next condition.
     */
    fun beginCond(): Long {
        for (c in 0 until NC) { cScratchNanos[c] = 0; cScratchCalls[c] = 0 }
        for (a in 0 until NA) armScratch[a] = 0
        acnScratchInv = 0
        return t()
    }

    /** Close one `applyConditionNarrowing`, flagging an identity result. */
    fun closeCond(t0: Long, identity: Boolean) {
        condCalls++
        if (timing) {
            val d = PassTiming.nowNanos() - t0
            nanos[S_COND] += d; calls[S_COND]++
            if (identity) { condIdentity++; condIdentityNanos += d }
        } else if (identity) condIdentity++
        val n = if (identity) cNanosIdent else cNanosNarrow
        val c = if (identity) cCallsIdent else cCallsNarrow
        for (i in 0 until NC) { n[i] += cScratchNanos[i]; c[i] += cScratchCalls[i] }
        if (identity) acnInvIdent += acnScratchInv else {
            acnInvNarrow += acnScratchInv
            for (a in 0 until NA) armNarrow[a] += armScratch[a]
        }
        if (acnScratchInv > acnFanOutMax) acnFanOutMax = acnScratchInv
    }

    /** One `applyConditionNarrowing` invocation, recursive ones included. */
    fun arm(a: Int) {
        acnInvocations++; acnScratchInv++
        armAll[a]++; armScratch[a]++
    }

    /** Start a nested sub-measure, or 0 when [COARSE]/[OFF]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun t(): Long = if (mode == ON || mode == DEEP) PassTiming.nowNanos() else 0L

    /** Close a nested sub-measure opened at [t0]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun close(sec: Int, t0: Long) {
        if (mode != ON && mode != DEEP) return
        nanos[sec] += PassTiming.nowNanos() - t0
        calls[sec]++
    }

    /** Close an intra-`applyConditionNarrowing` row opened at [t0]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun closec(sec: Int, t0: Long) {
        if (mode != ON && mode != DEEP) return
        cScratchNanos[sec] += PassTiming.nowNanos() - t0
        cScratchCalls[sec]++
    }

    /** Start a [DEEP]-only row (`getReferencePath`), or 0 otherwise. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun tDeep(): Long = if (mode == DEEP) PassTiming.nowNanos() else 0L

    /** Close a [DEEP]-only row opened at [t0]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun closeDeep(sec: Int, t0: Long) {
        if (mode != DEEP) return
        cScratchNanos[sec] += PassTiming.nowNanos() - t0
        cScratchCalls[sec]++
    }

    private fun ms(n: Long) = (n / 1_000_000).toString().padStart(5)

    /** `a / d` to two decimals, without `String.format` (JVM-only). */
    private fun ratio(a: Long, d: Long): String {
        if (d <= 0L) return "-"
        val hundredths = a * 100 / d
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }

    fun report(): String = buildString {
        appendLine("== (CALL.3) intra-walk attribution: narrowTypeFromFlow ==")
        appendLine("mode: ${if (mode == COARSE) "COARSE (anchor only)" else "ON"}")
        appendLine(
            "walks: $walks   of which >= 1 ms: $hugeWalks   " +
                "invocations: $invocations (tail $hugeInvocations)"
        )
        appendLine("-- (i) ARRIVALS versus DISTINCT flow nodes --")
        appendLine(
            "  all walks : arrivals=$arrivals distinct=$distinct " +
                "revisitFactor=${ratio(arrivals, distinct)}  " +
                "(${if (walks > 0) arrivals / walks else 0} / ${
                    if (walks > 0) distinct / walks else 0
                } per walk)"
        )
        appendLine(
            "  >= 1 ms   : arrivals=$hugeArrivals distinct=$hugeDistinct " +
                "revisitFactor=${ratio(hugeArrivals, hugeDistinct)}  " +
                "(${if (hugeWalks > 0) hugeArrivals / hugeWalks else 0} / ${
                    if (hugeWalks > 0) hugeDistinct / hugeWalks else 0
                } per walk; max $hugeArrivalsMax / $hugeDistinctMax)"
        )
        appendLine("-- the intra-walk memo, split by why a probe did not serve --")
        appendLine(
            "  all walks : served=$memoServe missAbsent=$memoMissAbsent " +
                "missTooShallow=$memoMissDepth  seenCycleBail=$seenCycle"
        )
        appendLine(
            "  >= 1 ms   : served=$hugeMemoServe missAbsent=$hugeMemoMissAbsent " +
                "missTooShallow=$hugeMemoMissDepth  seenCycleBail=$hugeSeenCycle"
        )
        appendLine(
            "  probe steps (open addressing): memo=$probeStepsMemo seen=$probeStepsSeen " +
                "tail=$hugeProbeSteps (${
                    if (hugeArrivals > 0) hugeProbeSteps / hugeArrivals else 0
                } per tail arrival)"
        )
        appendLine(
            "  walks returning the DECLARED type unchanged: $identityWalks (tail $hugeIdentityWalks)"
        )
        appendLine(
            "  maxDepth reached: $maxDepth of NARROW_MAX_DEPTH=2000   " +
                "truncations: depth=$truncDepth budget=$truncBudget cycle=$seenCycle"
        )
        appendLine(
            "  applyConditionNarrowing: $condCalls calls, $condIdentity returned the INPUT " +
                "unchanged (${if (condCalls > 0) condIdentity * 100 / condCalls else 0}%, ${
                    ms(condIdentityNanos)
                } ms)"
        )
        appendLine("-- memo outcome by flow-node kind (served / absent / tooShallow) --")
        for (k in 0 until NKINDS) {
            val s = memoOutcomeByKind[0][k]
            val a = memoOutcomeByKind[1][k]
            val d = memoOutcomeByKind[2][k]
            if (s + a + d == 0L) continue
            appendLine(
                "  ${kindNames[k].padEnd(18)} all ${s.toString().padStart(8)} / ${
                    a.toString().padStart(8)
                } / ${d.toString().padStart(8)}   tail ${
                    hugeMemoOutcomeByKind[0][k].toString().padStart(7)
                } / ${hugeMemoOutcomeByKind[1][k].toString().padStart(7)} / ${
                    hugeMemoOutcomeByKind[2][k].toString().padStart(7)
                }"
            )
        }
        appendLine("-- arrivals by flow-node kind --")
        for (k in 0 until NKINDS) {
            if (arrivalsByKind[k] == 0L && hugeArrivalsByKind[k] == 0L) continue
            val a = arrivalsByKind[k]
            val h = hugeArrivalsByKind[k]
            appendLine(
                "  ${kindNames[k].padEnd(18)} ${a.toString().padStart(9)} all  " +
                    "${h.toString().padStart(9)} tail  " +
                    "(${if (arrivals > 0) a * 100 / arrivals else 0}% / ${
                        if (hugeArrivals > 0) h * 100 / hugeArrivals else 0
                    }%)"
            )
        }
        appendLine("-- applyConditionNarrowing: arm dispatch (all invocations / narrowing calls) --")
        appendLine(
            "  invocations: $acnInvocations   fan-out per outermost call: " +
                "narrowing ${ratio(acnInvNarrow, condCalls - condIdentity)} " +
                "identity ${ratio(acnInvIdent, condIdentity)} (max $acnFanOutMax)"
        )
        for (a in 0 until NA) {
            if (armAll[a] == 0L && armNarrow[a] == 0L) continue
            appendLine(
                "  ${armNames[a].padEnd(38)} ${armAll[a].toString().padStart(9)} all  ${
                    armNarrow[a].toString().padStart(9)
                } narrowing"
            )
        }
        if (timing) {
            appendLine("-- (ii) the per-arrival split (nested sub-measures, self time) --")
            for (s in 0 until N) {
                val c = calls[s]
                if (c == 0L) continue
                appendLine(
                    "  ${names[s].padEnd(38)} ${ms(nanos[s])} ms over ${
                        c.toString().padStart(9)
                    } = ${nanos[s] / c} ns each"
                )
            }
            var leaves = 0L
            for (s in 1 until N) leaves += nanos[s]
            appendLine("  sum of the rows below the anchor: ${ms(leaves)} ms")
            appendLine(
                "-- (CALL.4) inside applyConditionNarrowing, split by whether the " +
                    "OUTERMOST call narrowed --"
            )
            appendLine(
                "  ${"row".padEnd(38)}${"NARROWING ms".padStart(13)}${"calls".padStart(10)}${
                    "ns each".padStart(10)
                }${"IDENTITY ms".padStart(13)}${"calls".padStart(10)}${"ns each".padStart(10)}"
            )
            var sumN = 0L
            var sumI = 0L
            for (c in 0 until NC) {
                if (cCallsNarrow[c] == 0L && cCallsIdent[c] == 0L) continue
                sumN += cNanosNarrow[c]; sumI += cNanosIdent[c]
                appendLine(
                    "  ${cNames[c].padEnd(38)}${ms(cNanosNarrow[c])} ms${
                        cCallsNarrow[c].toString().padStart(10)
                    }${
                        (if (cCallsNarrow[c] > 0) cNanosNarrow[c] / cCallsNarrow[c] else 0)
                            .toString().padStart(10)
                    }${ms(cNanosIdent[c])} ms${cCallsIdent[c].toString().padStart(10)}${
                        (if (cCallsIdent[c] > 0) cNanosIdent[c] / cCallsIdent[c] else 0)
                            .toString().padStart(10)
                    }"
                )
            }
            val narrowingCalls = condCalls - condIdentity
            val narrowingNanos = nanos[S_COND] - condIdentityNanos
            appendLine(
                "  ${"SUM of the rows".padEnd(38)}${ms(sumN)} ms${" ".repeat(20)}${
                    ms(sumI)
                } ms"
            )
            appendLine(
                "  ${"the applyConditionNarrowing anchor".padEnd(38)}${ms(narrowingNanos)} ms${
                    narrowingCalls.toString().padStart(10)
                }${
                    (if (narrowingCalls > 0) narrowingNanos / narrowingCalls else 0)
                        .toString().padStart(10)
                }${ms(condIdentityNanos)} ms${condIdentity.toString().padStart(10)}${
                    (if (condIdentity > 0) condIdentityNanos / condIdentity else 0)
                        .toString().padStart(10)
                }"
            )
            appendLine(
                "  residue (dispatch + recursion + unbracketed): ${ms(narrowingNanos - sumN)} ms " +
                    "narrowing, ${ms(condIdentityNanos - sumI)} ms identity"
            )
        }
    }

    fun csv(): String = buildString {
        appendLine("section,reached,nanos")
        for (s in 0 until N) {
            if (calls[s] == 0L) continue
            appendLine("\"${names[s].trim()}\",${calls[s]},${nanos[s]}")
        }
        for (c in 0 until NC) {
            if (cCallsNarrow[c] != 0L) {
                appendLine("\"acn/narrowing/${cNames[c]}\",${cCallsNarrow[c]},${cNanosNarrow[c]}")
            }
            if (cCallsIdent[c] != 0L) {
                appendLine("\"acn/identity/${cNames[c]}\",${cCallsIdent[c]},${cNanosIdent[c]}")
            }
        }
        for (a in 0 until NA) {
            if (armAll[a] == 0L) continue
            appendLine("\"acn/arm/${armNames[a]}\",${armAll[a]},${armNarrow[a]}")
        }
    }
}

/**
 * (CALL.3)(a): probe-step counting for the two open-addressed structures the
 * narrowing walk touches once per ARRIVAL (`NarrowFlowMemo`, `NarrowSeen`).
 *
 * A timestamp pair per arrival would cost ~1.5 s against a 3.2 s population
 * (rounds 734/735: 86–89 ns per read, ~8.5 M arrivals), so the per-arrival
 * cost is priced in PROBE STEPS instead: a deterministic integer that is
 * exactly what an open-addressing table costs, and that the probe cannot
 * inflate. Off in production — [on] is set only for the duration of a depth-0
 * walk under `--narrowSections`.
 */
object NarrowProbe {
    var on: Boolean = false
    var steps: Long = 0

    /** The kind of the flow node the current arrival is at — see [NarrowSections.memoOutcome]. */
    var curKind: Int = 0

    /**
     * The per-walk DISTINCT flow-node set. It cannot be read off `NarrowSeen`:
     * that structure is PATH membership, emptied back to a mark at every
     * branch antecedent, so a node revisited on a sibling path re-enters it.
     * The distinct count needs a set that is only ever added to.
     */
    private const val EMPTY = Int.MIN_VALUE
    private var capacity = 1024
    private var slots = IntArray(capacity) { EMPTY }
    private var live = 0

    fun clearDistinct() {
        if (live != 0) { slots.fill(EMPTY); live = 0 }
    }

    /** Record an arrival at flow node [id]; true when it is the first this walk. */
    fun noteDistinct(id: Int): Boolean {
        val mask = capacity - 1
        var i = (id * -0x61c88647).let { it xor (it ushr 16) } and mask
        while (true) {
            val s = slots[i]
            if (s == id) return false
            if (s == EMPTY) {
                slots[i] = id
                live++
                if (live * 2 >= capacity) grow()
                return true
            }
            i = (i + 1) and mask
        }
    }

    private fun grow() {
        val old = slots
        capacity = capacity shl 1
        slots = IntArray(capacity) { EMPTY }
        val mask = capacity - 1
        for (s in old) {
            if (s == EMPTY) continue
            var i = (s * -0x61c88647).let { it xor (it ushr 16) } and mask
            while (slots[i] != EMPTY) i = (i + 1) and mask
            slots[i] = s
        }
    }
}

/**
 * (TYPE.2) round 738: the opt-in attribution INSIDE `spineCtaM3StatementAnchor`
 * — the sixth in the 732→737 sequence, and the first to open the third-largest
 * spine handler (2,900 ms, round 732's per-handler table) which no round had
 * touched. Round 737's by-caller table pointed here: `checkVarDeclAssignability`
 * is the compiler's largest single expression-typing origin (33,653 calls,
 * 11,933 top-level typings, 431 ms at 36 µs per initializer, factor 1.05 —
 * expensive, not redundant), and that typing is only ~15% of the handler.
 *
 * ## Two partitions in one object
 *
 * The handler and the function nest, so this object runs **two independent
 * running-section partitions** with their own depth counters:
 *
 * * **level A** — `ctaM3StmtAnchor`, split by CALLEE (`checkVarDeclAssignability`,
 *   `checkAssignmentExpression`, `walkFunctionBodiesInExpr`,
 *   `checkReturnAssignability`, `checkPropertyInitAssignability`,
 *   `checkFlowNoOverlapCondition`, `registerConstLiteralUnionNarrowing`) plus
 *   the ambient install/restore, so "what carries the 2,900 ms" is answered
 *   before anything inside a callee is opened.
 * * **level B** — `checkVarDeclAssignability`, split into its prologue walker
 *   groups, the source/target type computations, the narrowing site, the
 *   relation, the elaboration and the tail.
 *
 * Level B is nested inside level A's [A_VDECL] row, so B's partition total is
 * that row minus B's own boundary cost; both are reported and neither is
 * assumed.
 *
 * ## Calibration
 *
 * [COARSE] keeps only the anchors that still PARTITION each level, so an
 * ON-vs-COARSE pair divided by the extra boundary count prices a timestamp
 * read differentially — the only calibration rounds 734/735 found trustworthy
 * (the in-situ empty span over-read by 3.6× and 4.4× in consecutive rounds and
 * is reported here for the record only).
 */
object CtaSections {

    const val OFF = 0
    const val ON = 1

    /** Anchors only — the calibration counterpart of [ON]. */
    const val COARSE = 2

    /** Opt-in; [OFF] in production. Set by `--ctaSections` / `--ctaSectionsCoarse`. */
    var mode: Int = OFF

    // ── level A: the whole handler, by callee ────────────────────────────────
    /**
     * The handler's own ELIGIBILITY decision — the kind test, the parent test
     * and the `ctaM3NestedChainOk` / `ctaM3FnBodyAnchorScope` / `ctaM3NearestList`
     * parent-chain climbs — plus everything after an anchor returns. This is the
     * CONSULTATION cost § 0 named, isolated: it is paid at every reached node,
     * including the ones that do not anchor.
     */
    const val A_GATE = 0
    /** Frame lookup, the twelve ambient saves, the installs, the ns pushes. */
    const val A_SETUP = 1
    /** `checkVarDeclAssignability` (all four dispatch branches). */
    const val A_VDECL = 2
    /** `registerConstLiteralUnionNarrowing` (const declaration lists only). */
    const val A_CONSTNARROW = 3
    /** `contextualizeFnExprFromAnnotation` + `walkFunctionBodiesInExpr`. */
    const val A_WALKFN = 4
    /** `checkAssignmentExpression` (var-init `=`, expression statements). */
    const val A_ASSIGN = 5
    /** `checkReturnAssignability`. */
    const val A_RETURN = 6
    /** `checkPropertyInitAssignability` (the cta-m3k class-member arm). */
    const val A_PROPINIT = 7
    /** `checkFlowNoOverlapCondition` (the cta-m3j if-condition arm). */
    const val A_IFCOND = 8
    /** The `when` dispatch, the declaration loop, `withCtaFrameLocals`. */
    const val A_DISPATCH = 9
    /** The `finally`: diagnostic truncation, ns pops, the twelve restores. */
    const val A_RESTORE = 10

    // ── level B: checkVarDeclAssignability, in source order ───────────────────
    /** The `ObjectBindingPattern` branch (five destructuring walkers). */
    const val B_BINDPAT = 11
    /** Variance + B526 + TS2820/B554/B470 — the first prologue group. */
    const val B_PRO1 = 12
    /** The three B482 weak-type walkers + B582. */
    const val B_WEAK = 13
    /** B286/B422/B294/B296/B298 — the JS/union/objlit prologue group. */
    const val B_PRO2 = 14
    /** B101/B206/B181/B208 — the last prologue group before the annotation split. */
    const val B_PRO3 = 15
    /** The `typeAnnotation == null` branch: infer the type of an unannotated init. */
    const val B_UNANNOT = 16
    /** `resolveSimpleTypeName` + the varTypes / currentLocalTypes recordings. */
    const val B_RECORD = 17
    /** The `noUncheckedIndexedAccess` block. */
    const val B_NUIA = 18
    /** `null!` shape + the B85.3 ternary-vs-TypeLiteral block. */
    const val B_PRE2 = 19
    /** `getTypeFromTypeNode(typeAnnotation)` — the TARGET type. */
    const val B_TARGET = 20
    /** B590 clodule + B96-nested + B231 + class-ident + class-expression blocks. */
    const val B_NESTED = 21
    /** The SOURCE type: `literalTypeOfExpression`/`getTypeOfExpression` + 17.43. */
    const val B_SRCTYPE = 22
    /** The flow-narrowing block (`getNarrowedTypeForReference`, both arms). */
    const val B_NARROW = 23
    /** Foreign-TP gate + B112 construct-sig + B207 ternary-of-functions. */
    const val B_MID = 24
    /** `canUseTypeEngine` + `checkTypeRelatedTo` — the ASSIGNABILITY RELATION. */
    const val B_RELATION = 25
    /** B103 through the array/objlit excess-property walkers (~30 gates). */
    const val B_POST = 26
    /** The 408-line `canUse && !isAssignable` TS2322 elaboration + emission. */
    const val B_ELAB = 27
    /** The numeric-literal recording + the `declaredTypeStr` tail. */
    const val B_TAIL = 28

    /** The first index that is a nested sub-measure rather than a partition row. */
    const val FIRST_NESTED = 29

    // ── nested sub-measures (INSIDE the rows above) ───────────────────────────
    /** `getTypeOfExpression(init)` alone, inside [B_SRCTYPE]. */
    const val N_GET_TYPE_OF_EXPR = 29
    /** `getNarrowedTypeForReference` alone, inside [B_NARROW]. */
    const val N_NARROW_CALL = 30
    /** The narrowing block's confirming `checkTypeRelatedTo`, inside [B_NARROW]. */
    const val N_NARROW_REL = 31
    /** `getTypeFromTypeNode(typeAnnotation)` alone, inside [B_TARGET]. */
    const val N_TYPE_NODE = 32
    /** `canUseTypeEngine(sourceType, targetType)` alone, inside [B_RELATION]. */
    const val N_CANUSE = 33
    /** The `checkTypeRelatedTo(sourceType, targetType)` call itself. */
    const val N_REL_CALL = 34
    /** Level-B walks whose narrowing returned the INPUT type unchanged. */
    const val N_NARROW_IDENTITY = 35

    /** Level A's wrapper transition. Probe-only; absent in production. */
    const val A_ENTRY = 36
    /** Level B's wrapper transition. Probe-only; absent in production. */
    const val B_ENTRY = 37

    /** The FIRST empty boundary span of an invocation — not steady state. */
    const val OVERHEAD_FIRST = 38
    /** In-situ steady-state empty boundaries; a pessimistic upper bound. */
    const val OVERHEAD = 39

    const val N = 40

    val names: Array<String> = arrayOf(
        "A: eligibility gate + parent climbs",
        "A: frame + ambient install + ns push",
        "A: checkVarDeclAssignability",
        "A: registerConstLiteralUnionNarrowing",
        "A: walkFunctionBodiesInExpr",
        "A: checkAssignmentExpression",
        "A: checkReturnAssignability",
        "A: checkPropertyInitAssignability",
        "A: checkFlowNoOverlapCondition",
        "A: dispatch + decl loop",
        "A: finally (truncate + restore)",
        "B: ObjectBindingPattern branch",
        "B: prologue 1 (variance/B526/2820/554/470)",
        "B: prologue weak (B482 x3 + B582)",
        "B: prologue 2 (B286/422/294/296/298)",
        "B: prologue 3 (B101/206/181/208)",
        "B: unannotated-init inference",
        "B: varTypes / localTypes recording",
        "B: noUncheckedIndexedAccess block",
        "B: null! + B85.3 ternary block",
        "B: getTypeFromTypeNode (TARGET)",
        "B: clodule/B96/B231/class blocks",
        "B: SOURCE type computation",
        "B: flow narrowing block",
        "B: foreign-TP + B112 + B207",
        "B: canUseTypeEngine + RELATION",
        "B: post-relation walkers (~30)",
        "B: TS2322 elaboration (408 ln)",
        "B: tail (numeric literal + varTypes)",
        "  of which getTypeOfExpression(init)",
        "  of which getNarrowedTypeForReference",
        "  of which the narrowing confirm relation",
        "  of which getTypeFromTypeNode",
        "  of which canUseTypeEngine",
        "  of which checkTypeRelatedTo (the relation)",
        "  of which narrow calls returning the INPUT",
        "  A wrapper transition (probe-only)",
        "  B wrapper transition (probe-only)",
        "  probe boundary, first of the invocation",
        "  probe boundary (in situ, steady state)",
    )

    /**
     * [COARSE]'s active boundaries: the two entry anchors plus one interior
     * anchor per level, so each level's partition still spans the same wall
     * time while every other boundary costs a static read and a not-taken
     * branch instead of a timestamp pair.
     */
    val coarseAnchor: BooleanArray = BooleanArray(N).also {
        it[A_GATE] = true; it[A_VDECL] = true; it[A_ENTRY] = true
        it[B_BINDPAT] = true; it[B_ENTRY] = true
    }

    var nanos: LongArray = LongArray(N)
    var calls: LongArray = LongArray(N)

    /** Level-A invocations (`ctaM3StmtAnchor`, nested ones excluded). */
    var invocationsA: Long = 0
    /** Level-B invocations (`checkVarDeclAssignability`). */
    var invocationsB: Long = 0
    /** Level-B invocations reached from level A rather than a legacy walker. */
    var invocationsBFromA: Long = 0
    /** Declarations visited by level A's `VariableStatement` loops. */
    var declarations: Long = 0

    /** Level-A invocations by statement kind: var / expr / return / if / property / other. */
    var stmtKind: LongArray = LongArray(6)

    /** Level-A invocations by mode: normal list arm / bareSurface / recordOnly. */
    var anchorMode: LongArray = LongArray(3)

    /** Level-B exits by the row they returned inside — the exit profile. */
    var exitIn: LongArray = LongArray(N)

    /** Cost distribution of level-B invocations: `[<10 us, <100 us, <1 ms, >=1 ms]`. */
    var vdBucketCalls: LongArray = LongArray(4)
    var vdBucketNanos: LongArray = LongArray(4)

    /** Level A's running section and start timestamp; `-1` = none open. */
    var curA: Int = -1
    var curTA: Long = 0
    var depthA: Int = 0

    /** Level B's running section and start timestamp; `-1` = none open. */
    var curB: Int = -1
    var curTB: Long = 0
    var depthB: Int = 0
    var startB: Long = 0

    // ── level C: checkReturnAssignability, (ENGINE.1) site 2 ──────────────────
    // Its OWN index space and arrays, so the level-A/B layout — and the pins that
    // assert it — are untouched. Rows are in source order; the classification into
    // engine / dedicated-walker / bookkeeping lives in
    // `docs/perf/engine-rule-price.md`, not here.
    /** The wrapper transition. Probe-only; absent in production. */
    const val C_ENTRY = 0
    /** The generator `TReturn` unwrap and its re-entry. */
    const val C_GEN = 1
    /** The nullish-alias, literal-union and QualifiedName-suggestion guards. */
    const val C_FIRE1 = 2
    /** `arrayLiteralSatisfiesTupleTarget`. */
    const val C_TUPLE = 3
    /** `getTypeFromTypeNode(returnTypeNode)` — the TARGET type. */
    const val C_TARGET = 4
    /** `checkConditionalReturnBranches` — the per-branch positional check. */
    const val C_CONDBR = 5
    /** Contextual-type selection for an objlit / array-literal return. */
    const val C_CTX = 6
    /** The SOURCE type. */
    const val C_SRCTYPE = 7
    /** The flow-narrowing block and its confirming relation. */
    const val C_NARROW = 8
    /** The foreign-TP gate and the narrow-verified exit. */
    const val C_FTP = 9
    /** The objlit / array / arrow / construct-signature guard cluster. */
    const val C_WALKERS = 10
    /** `canUseTypeEngine` + `checkTypeRelatedTo` — the ASSIGNABILITY RELATION. */
    const val C_RELATION = 11
    /** The two guards between `canUseForReturn` and the relation's use. */
    const val C_MIDGUARD = 12
    /** The TS2322 elaboration and emission. */
    const val C_ELAB = 13
    /** The legacy string-based fallback tail. */
    const val C_STRTAIL = 14

    const val NC = 15

    val cNames: Array<String> = arrayOf(
        "C: wrapper transition",
        "C: generator TReturn unwrap",
        "C: nullish-alias / literal-union / qualified-name guards",
        "C: arrayLiteralSatisfiesTupleTarget",
        "C: getTypeFromTypeNode — the TARGET",
        "C: checkConditionalReturnBranches",
        "C: contextual-type selection",
        "C: the SOURCE type",
        "C: flow narrowing",
        "C: foreign-TP gate",
        "C: objlit / array / arrow guard cluster",
        "C: canUseTypeEngine + checkTypeRelatedTo",
        "C: post-canUse guards",
        "C: TS2322 elaboration + emission",
        "C: string-based fallback",
    )

    var cNanos: LongArray = LongArray(NC)
    var cCalls: LongArray = LongArray(NC)
    var cExitIn: LongArray = LongArray(NC)
    var invocationsC: Long = 0
    var curC: Int = -1
    var curTC: Long = 0
    var depthC: Int = 0

    // ── level D: walkFunctionBodiesInExpr, round 756 ──────────────────────────
    // The last unopened region the (TYPE.2) attribution arc pointed at: level A's
    // [A_WALKFN] row, 181 ms over 28,940 openings. Its OWN index space, so the
    // level-A/B/C layouts — and the pins that assert them — are untouched.
    //
    // **This level is RECURSIVE, which levels A-C were not**, so it cannot use
    // their "depth != 1 ⇒ return" shape (that would charge the whole recursive
    // descent to whichever row happened to be open at depth 1, i.e. to the
    // dispatch row, and answer nothing). Instead [beginD] hands the caller's
    // running row back to [endD], which reopens it: every row is therefore SELF
    // time, exclusive of nested `walkFunctionBodiesInExpr` invocations, and the
    // rows sum to the walk's true total. The consequence to keep in mind when
    // reading [D_FNEXPR]/[D_ARROW]: `checkFunctionBody` walks statements and
    // those statements re-enter this walker, so a body row is the body's own
    // checking MINUS the nested walks it spawns — which are themselves in the
    // table, one row down.
    //
    // Level D is active only inside the window the two [A_WALKFN] call sites open
    // ([inWalkFn]), so its total is directly comparable to that row and the
    // partition is a cross-check rather than a claim; invocations reached from
    // anywhere else (`checkFunctionBody` under any other handler) are COUNTED in
    // [invocationsDOutside] and not timed. The window is an explicit flag rather
    // than `curA == A_WALKFN` precisely so that it survives [COARSE], where level
    // A's interior anchors do not fire — that is what makes level D's own
    // ON-vs-COARSE differential possible.

    /** The wrapper transition. Probe-only; absent in production. */
    const val D_ENTRY = 0
    /** The `when` selection and every pure pass-through arm — the walk itself. */
    const val D_DISPATCH = 1
    /** `checkFunctionBody` from the `FunctionExpression` arm. */
    const val D_FNEXPR = 2
    /** `checkFunctionBody` from the `ArrowFunction` arm (block bodies only). */
    const val D_ARROW = 3
    /**
     * (FN.1) round 757: the parameter/type-parameter scope an EXPRESSION-bodied
     * arrow installs before its body is descended into. The descent itself is a
     * nested invocation and is therefore NOT in this row — this is the setup
     * only, which is the whole of the work the fix ADDS at this level.
     */
    const val D_ARROW_EXPR_SCOPE = 4
    /** `getTypeOfObjectLiteral` — the lazy `this` type of an object literal. */
    const val D_OBJLIT_THIS = 5
    /** `walkObjectLiteralMemberBody` — JS-like object-literal methods/accessors. */
    const val D_OBJLIT_MEM = 6
    /** B585 object-literal contextual type nodes (index-sig alias + member). */
    const val D_OBJLIT_CTX = 7
    /** B210 `calleeDeclaredCtxParams` — the callee's declared parameter list. */
    const val D_CTXPARAMS = 8
    /** B210 `contextualizeFnExprFromAnnotation` — the synthesized annotated fn. */
    const val D_CTXFN = 9
    /** B585 `objLitArgCalleeParamTypeNode` — a call argument's contextual node. */
    const val D_ARGCTX = 10

    const val ND = 11

    val dNames: Array<String> = arrayOf(
        "D: wrapper transition",
        "D: dispatch + pass-through arms (the walk)",
        "D: checkFunctionBody — FunctionExpression",
        "D: checkFunctionBody — ArrowFunction",
        "D: (FN.1) expression-bodied arrow param scope",
        "D: getTypeOfObjectLiteral (objlit `this`)",
        "D: walkObjectLiteralMemberBody (JS)",
        "D: B585 objlit contextual type nodes",
        "D: B210 calleeDeclaredCtxParams",
        "D: B210 contextualizeFnExprFromAnnotation",
        "D: B585 objLitArgCalleeParamTypeNode",
    )

    // The arm census. Round 755's finding came from one of these, not from a
    // millisecond: an arm with a conspicuous semantics and no invocations.
    const val DA_FNEXPR = 0
    const val DA_ARROW_BLOCK = 1
    /**
     * An arrow whose body is an EXPRESSION. Until round 757 this arm walked
     * NOTHING — see [D_ARROW_EXPR_SCOPE]; it now descends into the body.
     */
    const val DA_ARROW_EXPR = 2
    const val DA_ARRAY = 3
    const val DA_OBJLIT = 4
    const val DA_UNWRAP = 5
    const val DA_BINARY = 6
    const val DA_CALL = 7
    const val DA_NEW = 8
    const val DA_COND = 9
    const val DA_UNARY = 10
    const val DA_PROPACCESS = 11
    const val DA_ELEMACCESS = 12
    const val DA_TEMPLATE = 13
    const val DA_TAGGED = 14
    const val DA_COMMA = 15
    /** No arm matched — an identifier, a literal, a class expression, … */
    const val DA_LEAF = 16

    const val NDA = 17

    val dArmNames: Array<String> = arrayOf(
        "FunctionExpression (body walked)",
        "ArrowFunction, block body (walked)",
        "ArrowFunction, expression body (walked since 757)",
        "ArrayLiteralExpression",
        "ObjectLiteralExpression",
        "Paren/As/TypeAssertion/Satisfies/NonNull",
        "BinaryExpression",
        "CallExpression",
        "NewExpression",
        "ConditionalExpression",
        "Spread/Await/Yield/Void/Delete/TypeOf/Prefix/Postfix",
        "PropertyAccessExpression",
        "ElementAccessExpression",
        "TemplateExpression",
        "TaggedTemplateExpression",
        "CommaListExpression",
        "leaf (no arm — identifier/literal/class expr/…)",
    )

    var dNanos: LongArray = LongArray(ND)
    var dCalls: LongArray = LongArray(ND)
    var dArm: LongArray = LongArray(NDA)
    /** Nodes visited by the walk inside [A_WALKFN], at every depth. */
    var invocationsD: Long = 0
    /** Of those, the OUTERMOST ones — must equal `calls[A_WALKFN]`. */
    var outermostD: Long = 0
    /** Deepest recursion reached inside [A_WALKFN]. */
    var maxDepthD: Int = 0
    /** Invocations reached from anywhere else; counted, never timed. */
    var invocationsDOutside: Long = 0
    /** Level D's window: open only across the two [A_WALKFN] call sites. */
    var inWalkFn: Boolean = false
    var curD: Int = -1
    var curTD: Long = 0
    var depthD: Int = 0

    /** [beginD]'s "not measuring" sentinel — distinct from "no caller row" (-1). */
    const val D_INACTIVE = -2

    // ── level E: checkAssignmentExpression, (ENGINE.1) site 3 ─────────────────
    // The last of the three largest assignability sites, and the only one whose
    // partition (ENGINE.1) still owed. Its OWN index space and arrays, so the
    // level-A/B/C/D layouts — and the pins asserting them — are untouched.
    //
    // The function IS recursive, but at exactly one place: `a = b = c` descends
    // into the chained right-hand assignment at the very top. That single site
    // gets its own row, [E_RECURSE], and [atE] keeps the round-756 "depth != 1 ⇒
    // return" shape — so the whole nested descent is charged to [E_RECURSE] of
    // the OUTERMOST invocation, every other row stays exclusive of recursion,
    // and the rows still sum to the site's true total. (Level D needed the
    // hand-back shape because its recursion is spread across sixteen arms; here
    // a dedicated row is both simpler and exactly attributed.)
    //
    // Under [COARSE] the whole invocation stays in [E_ENTRY] (because [atE] is
    // [ON]-only), which is level E's own calibration counterpart.

    /** The wrapper transition. Probe-only; absent in production. */
    const val E_ENTRY = 0
    /** `a = b = c` — the nested invocation, charged whole to the outer one. */
    const val E_RECURSE = 1
    /** 16.4dp: `arguments = <primitive>` in a non-arrow body. */
    const val E_ARGS = 2
    /** The sort-comparator / deeply-nested-objlit / mutually-recursive guards. */
    const val E_FIRE1 = 3
    /** 16.4dr + typeOfPrototype: `X.prototype.m = …`. */
    const val E_PROTO = 4
    /** Identifier target: the array-literal and identifier-RHS guard clusters. */
    const val E_IDLIT = 5
    /** B73.1/B155 module-alias `typeof import("X")` shapes. */
    const val E_MODULE = 6
    /** B236 optional-vs-required presence rule for an embedded-lib pair. */
    const val E_B236 = 7
    /** The TARGET type: annotation, local scope, and the tuple-node check. */
    const val E_TTRESOLVE = 8
    /** The foreign-TP TARGET gate and the B8.1 `never`-target block. */
    const val E_FTP = 9
    /** A class-value RHS against the target's construct signature. */
    const val E_CTORID = 10
    /** The SOURCE type (contextual install + `literalTypeOfExpression`/`getTypeOfExpression`). */
    const val E_SRCTYPE = 11
    /** The flow-narrowing block and its confirming relation. */
    const val E_NARROW = 12
    /** The foreign-TP SOURCE gate and the index-signature literal-value check. */
    const val E_MID = 13
    /** The construct-signature / call-signature guard blocks. */
    const val E_SIGS = 14
    /** The object-literal guard block. */
    const val E_OBJLIT = 15
    /** The strictNullChecks union block and the array-literal guards. */
    const val E_UNION = 16
    /** B175: a class-value RHS identifier. */
    const val E_B175 = 17
    /** B127: interface-vs-interface guards. */
    const val E_B127 = 18
    /** `canUseTypeEngine` + `checkTypeRelatedTo` — the ASSIGNABILITY RELATION. */
    const val E_RELATION = 19
    /** Post-relation walkers: index-sig, excess properties, array elements. */
    const val E_POST = 20
    /** The `canUse && !isAssignable` TS2322 elaboration and emission. */
    const val E_ELAB = 21
    /** The engine-confirmed literal-RHS exit that skips the legacy path. */
    const val E_LITTAIL = 22
    /** The legacy `varTypes` string-based fallback. */
    const val E_DECLSTR = 23
    /** `this.prop = value` — the varTypes class-property write path. */
    const val E_THIS = 24
    /** `x.prop = value` — `checkPropertyAccessAssignment`. */
    const val E_PA = 25
    /** `x[k] = value` — the element-access write paths. */
    const val E_ELEM = 26

    const val NE = 27

    val eNames: Array<String> = arrayOf(
        "E: wrapper transition",
        "E: chained-assignment recursion (nested invocations)",
        "E: `arguments = <primitive>` (16.4dp)",
        "E: sort-comparator / deeply-nested / mutually-recursive",
        "E: X.prototype.m = fn (16.4dr + typeOfPrototype)",
        "E: identifier target — array/identifier RHS guards",
        "E: module-alias typeof-import shapes (B73.1/B155)",
        "E: B236 optional-vs-required lib pair",
        "E: the TARGET type (annotation + local + tuple)",
        "E: foreign-TP target gate + never target (B8.1)",
        "E: class-value RHS vs construct signature",
        "E: the SOURCE type",
        "E: flow narrowing",
        "E: foreign-TP source gate + index-sig literal values",
        "E: construct/call-signature guard blocks",
        "E: object-literal guard block",
        "E: strictNullChecks union + array-literal guards",
        "E: B175 class-value RHS identifier",
        "E: B127 interface-vs-interface guards",
        "E: canUseTypeEngine + checkTypeRelatedTo",
        "E: post-relation walkers (index-sig/excess/array)",
        "E: TS2322 elaboration + emission",
        "E: engine-confirmed literal-RHS exit",
        "E: legacy varTypes string fallback",
        "E: this.prop = value",
        "E: x.prop = value",
        "E: x[k] = value",
    )

    var eNanos: LongArray = LongArray(NE)
    var eCalls: LongArray = LongArray(NE)
    var eExitIn: LongArray = LongArray(NE)
    /** Outermost `checkAssignmentExpression` invocations. */
    var invocationsE: Long = 0
    /** Nested (chained-assignment) invocations; counted, never partitioned. */
    var invocationsENested: Long = 0
    var curE: Int = -1
    var curTE: Long = 0
    var depthE: Int = 0

    fun reset() {
        nanos = LongArray(N)
        calls = LongArray(N)
        invocationsA = 0; invocationsB = 0; invocationsBFromA = 0; declarations = 0
        stmtKind = LongArray(6)
        anchorMode = LongArray(3)
        exitIn = LongArray(N)
        vdBucketCalls = LongArray(4)
        vdBucketNanos = LongArray(4)
        curA = -1; curTA = 0; depthA = 0
        curB = -1; curTB = 0; depthB = 0; startB = 0
        cNanos = LongArray(NC); cCalls = LongArray(NC); cExitIn = LongArray(NC)
        invocationsC = 0; curC = -1; curTC = 0; depthC = 0
        dNanos = LongArray(ND); dCalls = LongArray(ND); dArm = LongArray(NDA)
        invocationsD = 0; outermostD = 0; maxDepthD = 0; invocationsDOutside = 0
        curD = -1; curTD = 0; depthD = 0; inWalkFn = false
        eNanos = LongArray(NE); eCalls = LongArray(NE); eExitIn = LongArray(NE)
        invocationsE = 0; invocationsENested = 0
        curE = -1; curTE = 0; depthE = 0
    }

    // The entry points are `inline` so a production call is a static read plus a
    // not-taken branch rather than a call, matching [ArgSections].

    /** Open level A's partition for one invocation, starting at [A_ENTRY]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun beginA() {
        if (mode == OFF) return
        depthA++
        if (depthA != 1) return
        invocationsA++
        // Self-healing: an anchor that threw would otherwise leave level D's
        // window open for every later invocation.
        inWalkFn = false
        curA = A_ENTRY
        curTA = PassTiming.nowNanos()
    }

    /** Close level A's running section and start [sec]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun atA(sec: Int) {
        if (mode == OFF || depthA != 1) return
        if (mode == COARSE && !coarseAnchor[sec]) return
        val now = PassTiming.nowNanos()
        nanos[curA] += now - curTA
        calls[curA]++
        curA = sec
        curTA = now
    }

    /** Close whatever level-A section is still open. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun endA() {
        if (mode == OFF) return
        if (depthA == 1 && curA >= 0) {
            nanos[curA] += PassTiming.nowNanos() - curTA
            calls[curA]++
            curA = -1
        }
        depthA--
    }

    /** Open level B's partition for one invocation, starting at [B_ENTRY]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun beginB() {
        if (mode == OFF) return
        depthB++
        if (depthB != 1) return
        invocationsB++
        if (depthA == 1) invocationsBFromA++
        curB = B_ENTRY
        startB = PassTiming.nowNanos()
        curTB = startB
    }

    /** Close level B's running section and start [sec]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun atB(sec: Int) {
        if (mode == OFF || depthB != 1) return
        if (mode == COARSE && !coarseAnchor[sec]) return
        val now = PassTiming.nowNanos()
        nanos[curB] += now - curTB
        calls[curB]++
        curB = sec
        curTB = now
    }

    /**
     * Close whatever level-B section is still open, recording WHICH row the
     * invocation left in (the exit profile comes free, as in round 735) and
     * bucketing the invocation's total cost.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun endB() {
        if (mode == OFF) return
        if (depthB == 1 && curB >= 0) {
            val now = PassTiming.nowNanos()
            nanos[curB] += now - curTB
            calls[curB]++
            exitIn[curB]++
            val d = now - startB
            val b = if (d < 10_000L) 0 else if (d < 100_000L) 1 else if (d < 1_000_000L) 2 else 3
            vdBucketCalls[b]++; vdBucketNanos[b] += d
            curB = -1
        }
        depthB--
    }

    /** Open level C's partition for one invocation, starting at [C_ENTRY]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun beginC() {
        if (mode == OFF) return
        depthC++
        if (depthC != 1) return
        invocationsC++
        curC = C_ENTRY
        curTC = PassTiming.nowNanos()
    }

    /**
     * Close level C's running section and start [sec]. Under [COARSE] the whole
     * function stays in [C_ENTRY], so a COARSE run is level C's calibration
     * counterpart with exactly one boundary pair per invocation.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun atC(sec: Int) {
        if (mode != ON || depthC != 1) return
        val now = PassTiming.nowNanos()
        cNanos[curC] += now - curTC
        cCalls[curC]++
        curC = sec
        curTC = now
    }

    /** Close whatever level-C section is open, recording the exit row. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun endC() {
        if (mode == OFF) return
        if (depthC == 1 && curC >= 0) {
            cNanos[curC] += PassTiming.nowNanos() - curTC
            cCalls[curC]++
            cExitIn[curC]++
            curC = -1
        }
        depthC--
    }

    /**
     * Open level D's partition for one `walkFunctionBodiesInExpr` invocation,
     * CLOSING the caller's running row and returning it so [endD] can reopen it.
     * Returns [D_INACTIVE] when this invocation is not inside the [A_WALKFN] row
     * (or the probe is off), in which case [endD] must do nothing.
     *
     * Under [COARSE] the whole invocation stays in [D_ENTRY] (because [atD] is
     * [ON]-only), so a COARSE run is level D's calibration counterpart with
     * exactly one boundary pair per invocation.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun beginD(): Int {
        if (mode == OFF) return D_INACTIVE
        if (!inWalkFn) { invocationsDOutside++; return D_INACTIVE }
        invocationsD++
        if (depthD == 0) outermostD++
        depthD++
        if (depthD > maxDepthD) maxDepthD = depthD
        val prev = curD
        val now = PassTiming.nowNanos()
        if (prev >= 0) { dNanos[prev] += now - curTD; dCalls[prev]++ }
        curD = D_ENTRY
        curTD = now
        return prev
    }

    /** Close level D's running row and start [sec]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun atD(sec: Int) {
        if (mode != ON || curD < 0) return
        val now = PassTiming.nowNanos()
        dNanos[curD] += now - curTD
        dCalls[curD]++
        curD = sec
        curTD = now
    }

    /** Close this invocation's running row and reopen the caller's, [prev]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun endD(prev: Int) {
        if (mode == OFF || prev == D_INACTIVE) return
        val now = PassTiming.nowNanos()
        if (curD >= 0) { dNanos[curD] += now - curTD; dCalls[curD]++ }
        depthD--
        curD = prev
        curTD = now
    }

    /**
     * Open level E's partition for one OUTERMOST `checkAssignmentExpression`,
     * starting at [E_ENTRY]. A nested (chained-assignment) invocation is counted
     * in [invocationsENested] and left to the outer partition's [E_RECURSE] row.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun beginE() {
        if (mode == OFF) return
        depthE++
        if (depthE != 1) { invocationsENested++; return }
        invocationsE++
        curE = E_ENTRY
        curTE = PassTiming.nowNanos()
    }

    /** Close level E's running section and start [sec]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun atE(sec: Int) {
        if (mode != ON || depthE != 1) return
        val now = PassTiming.nowNanos()
        eNanos[curE] += now - curTE
        eCalls[curE]++
        curE = sec
        curTE = now
    }

    /** Close whatever level-E section is open, recording the exit row. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun endE() {
        if (mode == OFF) return
        if (depthE == 1 && curE >= 0) {
            eNanos[curE] += PassTiming.nowNanos() - curTE
            eCalls[curE]++
            eExitIn[curE]++
            curE = -1
        }
        depthE--
    }

    /** Count one `when` arm of `walkFunctionBodiesInExpr`. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun armD(arm: Int) {
        if (mode == OFF || curD < 0) return
        dArm[arm]++
    }

    /** Open level D's window — one of the two [A_WALKFN] call sites. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun enterWalkFn() { if (mode != OFF) inWalkFn = true }

    /** Close level D's window. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun exitWalkFn() { if (mode != OFF) inWalkFn = false }

    /** Start a NESTED sub-measure, or 0 when off. Never active under [COARSE]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun t(): Long = if (mode == ON) PassTiming.nowNanos() else 0L

    /** Close a NESTED sub-measure opened at [t0]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun close(sec: Int, t0: Long) {
        if (mode != ON) return
        nanos[sec] += PassTiming.nowNanos() - t0
        calls[sec]++
    }

    /**
     * Close one narrowing walk: charge it to [N_NARROW_CALL] and — when
     * [changed] is false — to [N_NARROW_IDENTITY], the upper bound on any
     * pre-test that could prove the walk unnecessary.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun closeNarrow(t0: Long, changed: Boolean) {
        if (mode != ON) return
        val d = PassTiming.nowNanos() - t0
        nanos[N_NARROW_CALL] += d; calls[N_NARROW_CALL]++
        if (!changed) { nanos[N_NARROW_IDENTITY] += d; calls[N_NARROW_IDENTITY]++ }
    }

    /** Count one declaration visited by a level-A `VariableStatement` loop. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun declaration() {
        if (mode == OFF || depthA != 1) return
        declarations++
    }

    /** Record the statement kind ([k]) and anchor mode ([m]) of a level-A invocation. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun noteAnchor(k: Int, m: Int) {
        if (mode == OFF || depthA != 1) return
        stmtKind[k]++
        anchorMode[m]++
    }

    fun report(): String = buildString {
        appendLine("== (TYPE.2) intra-handler attribution: spineCtaM3StatementAnchor / checkVarDeclAssignability ==")
        appendLine("mode: ${if (mode == COARSE) "COARSE (anchors only)" else "ON"}")
        appendLine(
            "level A invocations: $invocationsA (declarations $declarations)   " +
                "level B invocations: $invocationsB (of which $invocationsBFromA from level A)"
        )
        appendLine(
            "level A by statement kind: var=${stmtKind[0]} expr=${stmtKind[1]} return=${stmtKind[2]} " +
                "if=${stmtKind[3]} property=${stmtKind[4]} other=${stmtKind[5]}"
        )
        appendLine(
            "level A by mode: list=${anchorMode[0]} bare=${anchorMode[1]} recordOnly=${anchorMode[2]}"
        )
        val ovhCalls = calls[OVERHEAD]
        val ovh = if (ovhCalls > 0) nanos[OVERHEAD] / ovhCalls else 0L
        appendLine(
            if (ovhCalls > 0) "probe boundary overhead: $ovh ns in situ (over $ovhCalls empty sections)"
            else "probe boundary overhead: NOT measured in situ — rounds 734/735 found that " +
                "construction over-reads by 3.6-4.4x; calibrate by an ON-vs-COARSE differential"
        )
        var partA = 0L; var rawA = 0L; var bA = 0L
        for (s in A_GATE..A_RESTORE) { partA += nanos[s] - ovh * calls[s]; rawA += nanos[s]; bA += calls[s] }
        var partB = 0L; var rawB = 0L; var bB = 0L
        for (s in B_BINDPAT..B_TAIL) { partB += nanos[s] - ovh * calls[s]; rawB += nanos[s]; bB += calls[s] }
        appendLine(
            "level A partition: ${partA / 1_000_000} ms net, ${rawA / 1_000_000} ms raw over $bA boundaries"
        )
        appendLine(
            "level B partition: ${partB / 1_000_000} ms net, ${rawB / 1_000_000} ms raw over $bB boundaries"
        )
        appendLine("-- sections (disjoint per level, source order; ms net of probe overhead) --")
        for (s in 0 until N) {
            val c = calls[s]
            if (s == FIRST_NESTED) appendLine("-- nested sub-measures (INSIDE the rows above) --")
            if (c == 0L) continue
            val ns = nanos[s] - ovh * c
            appendLine(
                "  ${names[s].padEnd(42)} ${(ns / 1_000_000).toString().padStart(5)} ms net " +
                    "(${(nanos[s] / 1_000_000).toString().padStart(5)} raw) reached ${
                        c.toString().padStart(7)
                    } = ${if (c > 0) ns / c else 0} ns each" +
                    if (s in B_BINDPAT..B_TAIL && exitIn[s] > 0) ", exitedIn=${exitIn[s]}" else ""
            )
        }
        appendLine("-- checkVarDeclAssignability invocation cost distribution --")
        val labels = arrayOf("< 10 us", "10-100 us", "0.1-1 ms", ">= 1 ms")
        for (b in 0 until 4) {
            appendLine(
                "  ${labels[b].padEnd(12)} ${vdBucketCalls[b].toString().padStart(7)} invocations, " +
                    "${(vdBucketNanos[b] / 1_000_000).toString().padStart(5)} ms"
            )
        }
        appendLine(
            "-- (ENGINE.1) level C: checkReturnAssignability, $invocationsC invocations --"
        )
        var partC = 0L
        var bC = 0L
        for (s in 0 until NC) { partC += cNanos[s] - ovh * cCalls[s]; bC += cCalls[s] }
        appendLine(
            "level C partition: ${partC / 1_000_000} ms net, ${
                cNanos.sum() / 1_000_000
            } ms raw over $bC boundaries"
        )
        for (s in 0 until NC) {
            val c = cCalls[s]
            if (c == 0L) continue
            val ns = cNanos[s] - ovh * c
            appendLine(
                "  ${cNames[s].padEnd(56)} ${(ns / 1_000_000).toString().padStart(5)} ms net " +
                    "(${(cNanos[s] / 1_000_000).toString().padStart(5)} raw) reached ${
                        c.toString().padStart(7)
                    } = ${ns / c} ns each, exitedIn=${cExitIn[s]}"
            )
        }
        appendLine(
            "-- (TYPE.3) level D: walkFunctionBodiesInExpr, inside the A_WALKFN row only --"
        )
        appendLine(
            "nodes visited $invocationsD (outermost $outermostD, max depth $maxDepthD); " +
                "invocations elsewhere, counted not timed: $invocationsDOutside"
        )
        var partD = 0L
        var bD = 0L
        for (s in 0 until ND) { partD += dNanos[s] - ovh * dCalls[s]; bD += dCalls[s] }
        appendLine(
            "level D partition: ${partD / 1_000_000} ms net, ${
                dNanos.sum() / 1_000_000
            } ms raw over $bD boundaries"
        )
        // NB the count is boundary CLOSES, not invocations: a nested walker entry
        // closes whichever row is open, so a body row is closed once per
        // transition PLUS once per nested walk it spawns. Per-invocation costs
        // come from the arm census below, not from this column.
        for (s in 0 until ND) {
            val c = dCalls[s]
            if (c == 0L) continue
            val ns = dNanos[s] - ovh * c
            appendLine(
                "  ${dNames[s].padEnd(46)} ${(ns / 1_000_000).toString().padStart(5)} ms net " +
                    "(${(dNanos[s] / 1_000_000).toString().padStart(5)} raw) closed ${
                        c.toString().padStart(8)
                    } times"
            )
        }
        appendLine("-- level D arm census: which `when` arm each visited node took --")
        for (a in 0 until NDA) {
            appendLine("  ${dArmNames[a].padEnd(52)} ${dArm[a].toString().padStart(8)}")
        }
        appendLine(
            "-- (ENGINE.1) level E: checkAssignmentExpression, $invocationsE outermost " +
                "invocations ($invocationsENested nested, charged to E_RECURSE) --"
        )
        var partE = 0L
        var bE = 0L
        for (s in 0 until NE) { partE += eNanos[s] - ovh * eCalls[s]; bE += eCalls[s] }
        appendLine(
            "level E partition: ${partE / 1_000_000} ms net, ${
                eNanos.sum() / 1_000_000
            } ms raw over $bE boundaries"
        )
        for (s in 0 until NE) {
            val c = eCalls[s]
            if (c == 0L) continue
            val ns = eNanos[s] - ovh * c
            appendLine(
                "  ${eNames[s].padEnd(56)} ${(ns / 1_000_000).toString().padStart(5)} ms net " +
                    "(${(eNanos[s] / 1_000_000).toString().padStart(5)} raw) reached ${
                        c.toString().padStart(7)
                    } = ${ns / c} ns each, exitedIn=${eExitIn[s]}"
            )
        }
    }

    /** Machine-readable dump: one line per section. */
    fun csv(): String = buildString {
        appendLine("section,reached,nanos,exitedIn")
        for (s in 0 until N) {
            if (calls[s] == 0L) continue
            appendLine("\"${names[s].trim()}\",${calls[s]},${nanos[s]},${exitIn[s]}")
        }
        for (s in 0 until NC) {
            if (cCalls[s] == 0L) continue
            appendLine("\"${cNames[s].trim()}\",${cCalls[s]},${cNanos[s]},${cExitIn[s]}")
        }
        for (s in 0 until ND) {
            if (dCalls[s] == 0L) continue
            appendLine("\"${dNames[s].trim()}\",${dCalls[s]},${dNanos[s]},0")
        }
        for (a in 0 until NDA) {
            appendLine("\"D arm: ${dArmNames[a]}\",${dArm[a]},0,0")
        }
        for (s in 0 until NE) {
            if (eCalls[s] == 0L) continue
            appendLine("\"${eNames[s].trim()}\",${eCalls[s]},${eNanos[s]},${eExitIn[s]}")
        }
    }
}

/**
 * (FRONT.1) round 738: the FIRST attribution of the front end — `ARCHITECTURE
 * -RETHINK` § 0.1's stage 5, "~20% of the compile, unprofiled", carried
 * unmeasured since round 490 because the checker always dominated.
 *
 * The phases are per-FILE, not per-node, so a timestamp pair costs nothing
 * relative to what it brackets (78 program files, ~89 ns per read) and the
 * usual ON-vs-COARSE calibration is unnecessary. Two things DO need care and
 * are handled explicitly:
 *
 * * **The crawl is concurrent** (`readAndScanBatch`: read on the IO
 *   dispatcher, parse on `Dispatchers.Default`, `FRONTEND_CONCURRENCY` in
 *   flight), so a `+=` from the worker lambdas would race exactly the way
 *   `PassTiming.nodeKindHistogram` does. Instead each flow element carries its
 *   OWN read and parse nanos back on its `CrawledFile`, and the SINGLE-THREADED
 *   collector sums them after `toList()` — race-free and exact. [CRAWL] is the
 *   crawl's WALL span; [READ] and [PREPARSE] are CPU sums across workers, so
 *   `READ + PREPARSE > CRAWL` is expected and is itself the parallel speed-up.
 * * **JFR self-% is not a wall-clock price** (round 623: `computeLineStarts`
 *   showed 5.3% of samples and eliminating it measured −0.3%). Everything here
 *   is a wall span around a named phase, which is the thing JFR could not give.
 */
object FrontEnd {

    const val OFF = 0
    const val ON = 1

    /** Opt-in; [OFF] in production. Set by `--frontEnd`. */
    var mode: Int = OFF

    /** tsconfig load, `@types` acquisition and the root-file glob walk. */
    const val CONFIG = 0
    /** The import-graph crawl, WALL — concurrent inside. */
    const val CRAWL = 1
    /** Read + UTF-8→UTF-16 decode, summed across crawl workers (CPU, not wall). */
    const val READ = 2
    /** The crawl's pre-parse, summed across crawl workers (CPU, not wall). */
    const val PREPARSE = 3
    /** The core's own parse loop — FRESH parses only (a reused pre-parse is free). */
    const val PARSE = 4
    /** `extractRelativeImports`, called TWICE per program file. */
    const val IMPORTS = 5
    /** `binder.bind` over every program file. */
    const val BIND = 6
    /** `Checker(...)` construction + `getDiagnostics()` — the 80% for reference. */
    const val CHECK = 7
    /** Everything in the compile core after the checker: transform, emit, tails. */
    const val POST = 8
    /** `Transformer.transform` per program file — INSIDE [POST]. */
    const val TRANSFORM = 9
    /** `Emitter.emit` per program file — INSIDE [POST]. */
    const val EMIT = 10
    /** Declaration (`.d.ts`) emit per program file — INSIDE [POST]. */
    const val DECL_EMIT = 11

    const val N = 12

    val names: Array<String> = arrayOf(
        "config load + @types + root glob",
        "import-graph crawl (WALL)",
        "  of which read+decode (CPU sum)",
        "  of which pre-parse (CPU sum)",
        "core parse loop (fresh only)",
        "extractRelativeImports (x2/file)",
        "bind (all program files)",
        "checker construct + getDiagnostics",
        "post-checker (transform/emit/tails)",
        "  of which Transformer.transform",
        "  of which Emitter.emit",
        "  of which declaration emit",
    )

    var nanos: LongArray = LongArray(N)
    var calls: LongArray = LongArray(N)

    /** Files read by the crawl, and the total decoded UTF-16 length. */
    var filesRead: Long = 0
    var charsRead: Long = 0

    /** Core parse loop: pre-parses REUSED versus parsed FRESH. */
    var parsedReused: Long = 0
    var parsedFresh: Long = 0

    fun reset() {
        nanos = LongArray(N)
        calls = LongArray(N)
        filesRead = 0; charsRead = 0
        parsedReused = 0; parsedFresh = 0
    }

    /** Start a span, or 0 when off. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun t(): Long = if (mode == ON) PassTiming.nowNanos() else 0L

    /** Close a span opened at [t0]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun close(sec: Int, t0: Long) {
        if (mode != ON) return
        nanos[sec] += PassTiming.nowNanos() - t0
        calls[sec]++
    }

    /**
     * Add a crawl worker's own read/parse nanos. Called from the SINGLE-THREADED
     * collector after the concurrent flow has been drained — never from a worker.
     */
    fun addCrawlFile(readNanos: Long, parseNanos: Long, chars: Int) {
        if (mode != ON) return
        nanos[READ] += readNanos; calls[READ]++
        nanos[PREPARSE] += parseNanos; if (parseNanos > 0) calls[PREPARSE]++
        filesRead++; charsRead += chars
    }

    fun report(): String = buildString {
        appendLine("== (FRONT.1) front-end attribution ==")
        appendLine(
            "files read: $filesRead ($charsRead chars)   core parse loop: " +
                "$parsedReused reused / $parsedFresh fresh"
        )
        var total = 0L
        for (s in 0..POST) if (s != READ && s != PREPARSE) total += nanos[s]
        appendLine("phases (disjoint except the two crawl sub-sums): total ${total / 1_000_000} ms")
        for (s in 0 until N) {
            val c = calls[s]
            if (c == 0L) continue
            val pct = if (total > 0) nanos[s] * 1000 / total else 0
            appendLine(
                "  ${names[s].padEnd(38)} ${(nanos[s] / 1_000_000).toString().padStart(6)} ms " +
                    "(${(pct / 10).toString().padStart(3)}.${pct % 10}%) over ${c.toString().padStart(6)} calls"
            )
        }
        val frontEnd = nanos[CONFIG] + nanos[CRAWL] + nanos[PARSE] + nanos[IMPORTS] + nanos[BIND]
        appendLine(
            "FRONT END (config+crawl+parse+imports+bind): ${frontEnd / 1_000_000} ms = " +
                "${if (total > 0) frontEnd * 100 / total else 0}% of the measured total"
        )
    }

    fun csv(): String = buildString {
        appendLine("phase,calls,nanos")
        for (s in 0 until N) {
            if (calls[s] == 0L) continue
            appendLine("\"${names[s].trim()}\",${calls[s]},${nanos[s]}")
        }
    }
}

/**
 * (ENGINE.2) round 787 — the opt-in partition of the PROPERTY-ACCESS path, the
 * largest single block of checking work in this compiler and the one site the
 * (ENGINE.1) arc never reached.
 *
 * `cpaSpineLeave`'s two anchor rows measure **4,449 ms** on the compiler profile
 * (3,179 anchor-stmt + 1,270 owner-cond, `--spineSections`, round 787) — ~16% of
 * a check-only compile, against the 1,417 ms held by the three assignability
 * sites (ENGINE.1) priced. Round 733 attributed the HANDLER and stopped at
 * "88.4% of it is the pass's own checking work"; this partitions that work.
 *
 * **Three levels, two shapes.**
 *
 * * **Level P — `checkPropertyAccessInExpr`, and it RECURSES**, so it uses round
 *   756's hand-back shape rather than levels A-C/E's `depth != 1 => return`:
 *   [beginP] closes the caller's running row and RETURNS it, [endP] closes its own
 *   and reopens the caller's. Every row is therefore SELF time, exclusive of
 *   nested invocations, and the rows sum to the walk's true total. The count
 *   column is boundary CLOSES (a row is also closed by every nested entry made
 *   while it is open), so per-invocation populations come from [pArm], never
 *   from that column.
 * * **Level Q — `checkSinglePropertyAccess`**, the per-property-access leaf,
 *   which does not recurse: it keeps the `depth != 1 => return` shape and counts
 *   any nested invocation in [invocationsQNested] (a pin asserts that stays 0).
 * * **Level R — `checkMemberAccessMissing`** (round 789, (ENGINE.2c)): level Q's
 *   engine row, one ~1,965-line function entered 66,747 times at 34.3 us each,
 *   6x the next row of either level. Same non-recursive shape as level Q, plus an
 *   EXIT CENSUS ([rExitIn]) recording which row each call RETURNS from — that is
 *   what can see a gate which is cheap to evaluate but runs after expensive work,
 *   and [rExitWalk] restricts it to the calls that paid for a flow walk. It has
 *   TWO callers (property access and element access), so [invocationsR]
 *   legitimately exceeds [invocationsQ]; the difference is the element accesses.
 *
 * Level P is active only inside the window `cpaSpineLeave`'s three anchor blocks
 * open ([inCpa]), so its total is directly comparable to the `--spineSections`
 * rows and the partition is a CROSS-CHECK rather than a claim; invocations
 * reached from the legacy statement/class-member walkers are counted in
 * [invocationsPOutside] and never timed. The window is an explicit flag rather
 * than a "which row is open" test precisely so that it survives [COARSE].
 *
 * **[CENSUS] is a third mode and exists to keep a counter out of a timing run.**
 * G4 asks whether the walk visits the same `PropertyAccessExpression` twice; the
 * distinct-nodeId sets that answer it would otherwise be charged to whichever row
 * was open. Under [CENSUS] no timestamp is ever read — only invocations, arms and
 * the distinct sets — so the counters are deterministic and the timing run is
 * unpolluted. Counters decide, wall time confirms.
 */
object CpaSections {

    const val OFF = 0
    const val ON = 1

    /** Anchors only — the calibration counterpart of [ON]. */
    const val COARSE = 2

    /** Counters and distinct-node sets ONLY; no timestamp is ever read. */
    const val CENSUS = 3

    /** Opt-in; [OFF] in production. Set by `--cpaSections{,Coarse,Census}`. */
    var mode: Int = OFF

    // ── level P: checkPropertyAccessInExpr, in source order ───────────────────

    /** The wrapper transition. Probe-only; absent in production. */
    const val P_ENTRY = 0
    /** The `when` selection and every pure pass-through arm — the walk itself. */
    const val P_DISPATCH = 1
    /** `checkSinglePropertyAccess` — the per-property-access leaf (level Q). */
    const val P_SINGLE_PA = 2
    /** `checkSingleElementAccess` — the `x[k]` leaf. */
    const val P_SINGLE_EA = 3
    /** `cpaComputeArgCtxTypes` — contextual types for a call's arguments. */
    const val P_ARGCTX = 4
    /** The per-argument `contextualType` save/install/restore loop. */
    const val P_CALLARGS = 5
    /** The binary left-spine's own work (tuple bounds, destructuring-private). */
    const val P_BINARY = 6
    /**
     * The arrow / function-expression SCOPE bookkeeping: three `EpochMap`/
     * `EpochSet` copies, `populateParameterLocalTypes`, `applyBodyLocalShadowing`,
     * `applyAmbiguousBlockScopedLocals`. G3's row — the round's one candidate
     * lever, because none of it is checking work.
     */
    const val P_FNSCOPE = 7
    /** Contextual-parameter inference from the arrow's own contextual type. */
    const val P_FNCTX = 8
    /** `checkPropertyAccessInStatements` for a block-bodied arrow / fn expression. */
    const val P_FNBODY = 9
    /** The object-literal contextual-member resolution block. */
    const val P_OBJLIT_CTX = 10
    /** The `ClassExpression` arm: the anonymous class type and its member walks. */
    const val P_CLASSEXPR = 11

    const val NP = 12

    val pNames: Array<String> = arrayOf(
        "P: wrapper transition",
        "P: dispatch + pass-through arms (the walk)",
        "P: checkSinglePropertyAccess (level Q)",
        "P: checkSingleElementAccess",
        "P: cpaComputeArgCtxTypes",
        "P: call-argument ctx loop",
        "P: binary left-spine own work",
        "P: arrow/fn-expr SCOPE bookkeeping",
        "P: arrow/fn-expr contextual params",
        "P: block body (checkPropertyAccessInStatements)",
        "P: object-literal contextual members",
        "P: ClassExpression arm",
    )

    // The arm census — how often each arm is TAKEN. Never a population: round
    // 756 quoted an arm count as the population behind it and was 146x out.
    const val PA_PROPACCESS = 0
    const val PA_CALL = 1
    const val PA_BINARY = 2
    const val PA_COND = 3
    const val PA_UNWRAP = 4
    const val PA_ARROW = 5
    const val PA_FNEXPR = 6
    const val PA_NEW = 7
    const val PA_ELEMACCESS = 8
    const val PA_UNARY = 9
    const val PA_TEMPLATE = 10
    const val PA_ARRAYLIT = 11
    const val PA_OBJLIT = 12
    const val PA_TAGGED = 13
    const val PA_CLASSEXPR = 14
    const val PA_LEAF = 15

    const val NPA = 16

    val pArmNames: Array<String> = arrayOf(
        "PropertyAccessExpression",
        "CallExpression",
        "BinaryExpression",
        "ConditionalExpression",
        "Paren/As/NonNull/TypeAssertion/Satisfies/Spread",
        "ArrowFunction",
        "FunctionExpression",
        "NewExpression",
        "ElementAccessExpression",
        "Prefix/Postfix/Await/Delete/Void/TypeOf/Yield",
        "TemplateExpression",
        "ArrayLiteralExpression",
        "ObjectLiteralExpression",
        "TaggedTemplateExpression",
        "ClassExpression",
        "leaf (no arm — identifier/literal/…)",
    )

    // ── level Q: checkSinglePropertyAccess, in source order ───────────────────

    /** The wrapper transition. Probe-only; absent in production. */
    const val Q_ENTRY = 0
    /** TS1209 — `new A?.b()` with no argument list. */
    const val Q_TS1209 = 1
    /** TS2339 — `.prototype` on a `new` instance. */
    const val Q_PROTO = 2
    /** `emitTs2532ForOptionalChainInstantiationReceiver`. */
    const val Q_TS2532 = 3
    /** `emitTs18048ForOptionalPropertyAccessReceiver` (loop-aware narrowing). */
    const val Q_TS18048_OPT = 4
    /** `emitTs18048ForClosureCapturedUndefinedReceiver`. */
    const val Q_TS18048_CLO = 5
    /** The `super.X` cluster: TS2340/TS2855 + `emitTs2339ForMissingSuperMember`. */
    const val Q_SUPER = 6
    /** TS2748 — ambient const enum under `isolatedModules`. */
    const val Q_CONSTENUM = 7
    /** `checkPrivateMemberAccess` — TS2341. */
    const val Q_PRIVATE = 8
    /** `checkMemberAccessMissing` — TS2339/TS2551, the property-resolution ENGINE. */
    const val Q_MISSING = 9

    const val NQ = 10

    // -- level R: checkMemberAccessMissing, in source order ------------------
    // Round 789, (ENGINE.2c). Level Q's engine row is ONE ~1,965-line function
    // entered 66,747 times at 34.3 us each -- 6x the next row of either level and
    // the largest unopened leaf in the compile. Like level Q it does not recurse
    // ([invocationsRNested] is pinned at 0), so it keeps the `depth != 1 => return`
    // shape; unlike level Q it is reached from TWO callers (property access and
    // element access), so [invocationsR] exceeds level Q's count by the element
    // accesses and the two are cross-checked rather than assumed equal.

    /** The wrapper transition. Probe-only; absent in production. */
    const val R_ENTRY = 0
    /** Empty name, paren unwrap, the intersection-reduction `never` probe. */
    const val R_PRE = 1
    /** The two `currentShadowedNames` receiver blocks. */
    const val R_SHADOW = 2
    /** The three flow-graph receiver blocks (identifier / property access / `this`). */
    const val R_FLOW = 3
    /** The three identifier-receiver special cases. */
    const val R_IDENT = 4
    /** String / regex / empty-object-literal receivers. */
    const val R_LITERAL = 5
    /** `new X().p` -- the NewExpression receiver block. */
    const val R_NEW = 6
    /** `f().p` -- the CallExpression receiver block. */
    const val R_CALL = 7
    /** `a.b.p` / `a[k].p` -- the PropertyAccess / ElementAccess receiver block. */
    const val R_PAEA = 8
    /** `this.p` inside a static method. */
    const val R_STATIC = 9
    /** Computing the receiver type: the `this` and ArrayLiteral arms. */
    const val R_OT_THIS = 10
    /** Computing the receiver type: namespace-member / enum-member / cast emissions. */
    const val R_OT_PRE = 11
    /** Computing the receiver type: the narrowing-ELIGIBILITY gate. */
    const val R_OT_ELIG = 12
    /** Computing the receiver type: `getTypeOfExpression(receiver)` -- THE RAW TYPE. */
    const val R_OT_RAW = 13
    /** Computing the receiver type: union-receiver narrowing. */
    const val R_OT_UNION = 14
    /** Computing the receiver type: the non-Identifier receiver branch. */
    const val R_OT_NONIDENT = 15
    /** Computing the receiver type: identifier symbol resolution. */
    const val R_OT_IDENTSYM = 16
    /** Computing the receiver type: the resolved-symbol branch and its `else`. */
    const val R_OT_IDENT = 17
    /** `objectType !is Type.Object` / enum-flavored gates. */
    const val R_TYPEGATE = 18
    /** `resolveStructuredTypeMembers(objectType)` -- member resolution. */
    const val R_RESOLVE = 19
    /** The member-less-receiver block. */
    const val R_EMPTYPROPS = 20
    /** Post-type gates: `this`+interface, base types, Reference, RUNTIME_PROPERTIES. */
    const val R_POSTGATE = 21
    /** `getPropertyOfType` -- THE LOOKUP -- and the property-exists exit. */
    const val R_PROP = 22
    /** Late suppression blocks and the index-signature gates. */
    const val R_LATEGATE = 23
    /** Spelling suggestion, `typeToString`, message construction, emission. */
    const val R_EMIT = 24

    const val NR = 25

    val rNames: Array<String> = arrayOf(
        "R: wrapper transition",
        "R: pre (empty name, unwrap, intersection-never)",
        "R: shadowed-name receivers",
        "R: flow-graph receiver blocks",
        "R: identifier-receiver special cases",
        "R: string/regex/empty-objlit receivers",
        "R: NewExpression receiver",
        "R: CallExpression receiver",
        "R: PropertyAccess/ElementAccess receiver",
        "R: this-in-static-method",
        "R: type = this / ArrayLiteral arms",
        "R: type = ns-member/enum-member/cast emissions",
        "R: type = narrowing-eligibility gate",
        "R: type = getTypeOfExpression(receiver) RAW",
        "R: type = union-receiver narrowing",
        "R: type = non-Identifier receiver",
        "R: type = identifier symbol resolution",
        "R: type = resolved-symbol branch",
        "R: objectType gates (!is Object, enum-flavored)",
        "R: resolveStructuredTypeMembers",
        "R: member-less receiver block",
        "R: post-type gates (base/Reference/runtime props)",
        "R: getPropertyOfType (THE LOOKUP)",
        "R: late suppression + index signatures",
        "R: suggestion + typeToString + emission",
    )

    val qNames: Array<String> = arrayOf(
        "Q: wrapper transition",
        "Q: TS1209 new-expression optional chain",
        "Q: TS2339 .prototype on an instance",
        "Q: emitTs2532 optional-chain instantiation receiver",
        "Q: emitTs18048 optional-property receiver",
        "Q: emitTs18048 closure-captured receiver",
        "Q: super.X cluster (TS2340/2855/2339)",
        "Q: TS2748 ambient const enum",
        "Q: checkPrivateMemberAccess (TS2341)",
        "Q: checkMemberAccessMissing (THE ENGINE)",
    )

    /**
     * [COARSE]'s active boundaries: one entry anchor per level, so each level's
     * partition still spans the same wall time while every other boundary costs
     * a static read and a not-taken branch instead of a timestamp pair. Level P's
     * [beginP]/[endP] pair and level Q's [beginQ]/[endQ] pair always fire — that
     * is exactly the differential.
     */
    var pNanos: LongArray = LongArray(NP)
    var pCalls: LongArray = LongArray(NP)
    var pArm: LongArray = LongArray(NPA)
    var qNanos: LongArray = LongArray(NQ)
    var qCalls: LongArray = LongArray(NQ)
    var qExitIn: LongArray = LongArray(NQ)
    var rNanos: LongArray = LongArray(NR)
    var rCalls: LongArray = LongArray(NR)
    var rExitIn: LongArray = LongArray(NR)

    /**
     * The exit census RESTRICTED to the calls that launched an [R_FLOW] block-1
     * narrowing walk. This is the whole question the row poses: the walks defend
     * an emission that happens at the BOTTOM of the function, so a walker that
     * exits before any emission site paid for nothing. A row here is a candidate
     * POPULATION, never a measurement of recoverable time (round 788's law).
     */
    var rExitWalk: LongArray = LongArray(NR)
    /** Set by [noteRWalk] for the current invocation; read by [endR]. */
    var rWalked: Boolean = false

    /** Level-P invocations inside the [inCpa] window, at every depth. */
    var invocationsP: Long = 0
    /** Of those, the OUTERMOST ones — one per `cpaSpineLeave` anchor walk. */
    var outermostP: Long = 0
    /** Deepest recursion reached inside the window. */
    var maxDepthP: Int = 0
    /** Invocations reached from the legacy walkers; counted, never timed. */
    var invocationsPOutside: Long = 0
    /** Level-Q invocations (all of them, window or not). */
    var invocationsQ: Long = 0
    /** Nested level-Q invocations — a pin asserts this stays 0. */
    var invocationsQNested: Long = 0
    /** Level-R invocations — from BOTH callers (property access + element access). */
    var invocationsR: Long = 0
    /** Nested level-R invocations — a pin asserts this stays 0. */
    var invocationsRNested: Long = 0

    /**
     * G4: distinct `PropertyAccessExpression` nodes reaching level Q. [CENSUS] only.
     *
     * **Keyed by (file, nodeId), never by nodeId alone**: `indexSourceFile`
     * restarts `nodeId` at 0 for EVERY `SourceFile`, so a program-wide set of raw
     * ids silently collapses one node per file onto each id and inflates any
     * visits/distinct ratio by an unbounded factor. Round 787 measured 2.35x that
     * way before checking, and 1.42x once keyed correctly.
     */
    var distinctPa: HashSet<Long> = HashSet()
    /** G4: distinct nodes reaching level P inside the window. [CENSUS] only. */
    var distinctP: HashSet<Long> = HashSet()

    /** The (file, nodeId) key [distinctP]/[distinctPa] are keyed by. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun nodeKey(fileName: String, nodeId: Int): Long =
        (fileName.hashCode().toLong() shl 32) or (nodeId.toLong() and 0xFFFFFFFFL)

    // ── nested sub-measures, INSIDE the rows above ────────────────────────────
    // These exist because two rows of the level-P/Q partition are large enough to
    // be worth a follow-on and too coarse to act on: [P_ARGCTX] (346 ms over every
    // call expression in the program) and [Q_TS18048_CLO] (296 ms over every
    // property access). A row is a location; these say what is inside it.

    /** `cpaComputeArgCtxTypes` itself, excluding the [argCtxConsumable] predicate. */
    const val N_ARGCTX = 0
    /**
     * Of [N_ARGCTX], the calls whose ARGUMENT subtrees contain an arrow, a
     * function expression or an object literal — i.e. the only calls whose
     * computed contextual types anything can read. The complement is the prize of
     * a pre-gate, measured rather than inferred from a count.
     */
    const val N_ARGCTX_CONSUMABLE = 1
    /** B464: `getTypeOfExpression(recv)` — the round-489 pre-gate's own cost. */
    const val N_B464_TYPEOF = 2
    /** B464: the `closureStarts` innermost-closure scan. */
    const val N_B464_SCAN = 3
    /** B464: `getNarrowedTypeForReferenceFollowLoopEntry` — a FLOW WALK. */
    const val N_B464_NARROW = 4

    // Round 789: inside level R's dominant row, [R_FLOW] -- three suppression
    // blocks that run at EVERY property access and suppress 1,169 times.
    /** R_FLOW block 1: `getTypeOfExpression(receiver)`. */
    const val N_F1_RAW = 5
    /** R_FLOW block 1: the round-489 pre-gate -- two `getPropertyOfType` lookups. */
    const val N_F1_GATE = 6
    /** R_FLOW block 1: the PLAIN narrowing FLOW WALK past that pre-gate. */
    const val N_F1_WALK = 7
    /** R_FLOW block 2: the base type + `getFlowAt` pre-gate. */
    const val N_F2_PRE = 8
    /** R_FLOW block 2: the base-projection FLOW WALK. */
    const val N_F2_WALK = 9
    /** R_FLOW block 3: the whole `this`-receiver narrowing block. */
    const val N_F3 = 10
    /** R_FLOW block 1: the round-425 loop-entry RETRY walk. */
    const val N_F1_WALK2 = 11

    /**
     * (ENGINE.2d)(b) round 791: the whole DEFERRED suppression call, timed in
     * `checkMemberAccessMissing`'s wrapper. In production it opens only for a
     * call whose body appended a diagnostic, so its `calls` column IS the
     * deferral's yield: the population that still pays for the apparatus.
     * The `R_FLOW b*` rows below now report the SAME calls from inside it.
     */
    const val N_DEFER = 12

    // Round 792, (ENGINE.2e): level S -- a sub-partition of the FOUR biggest
    // level-R rows once (b) removed the row that was 42% of the function. Their
    // shares were all re-derived at HEAD before these were placed (law 1): the
    // top four are [R_OT_IDENT] 280, [R_OT_UNION] 271, [R_IDENT] 159 and
    // [R_PRE] 129 ms net, 68% of the function between them. A row is a location;
    // these say what is inside it.

    /** [R_PRE]: the B8.1 intersection-reduction `never` probe (the whole `run`). */
    const val N_PRE_ISECT = 13
    /** [R_IDENT] block 1: B589's clodule/lib-merge receiver scan. */
    const val N_ID_CLODULE = 14
    /** [R_IDENT] block 2: B586's `{}`/`Object`-annotated receiver block. */
    const val N_ID_EMPTYOBJ = 15
    /** [R_IDENT] block 3: `tryEmitEnumTypedIdentReceiverTs2339`. */
    const val N_ID_ENUMRECV = 16
    /** [R_OT_UNION]: the PLAIN narrowing flow walk over a union receiver. */
    const val N_U_PLAIN = 17
    /** [R_OT_UNION]: the round-424 loop-entry RETRY walk and its member fold,
     *  for the calls whose plain walk was NOT provably loop-free. */
    const val N_U_RETRY = 18
    /** [R_OT_UNION]: everything after the walks -- the TS2339 union elaboration. */
    const val N_U_ELAB = 19
    /** [R_OT_IDENTSYM]: `lookupPerFileForNode` for the receiver identifier. */
    const val N_IDSYM_LOOKUP = 20
    /** [R_OT_IDENT]: the specialised emission gates ABOVE `getTypeOfSymbol`. */
    const val N_OTI_GATES = 21
    /** [R_OT_IDENT]: `getTypeOfSymbol(identSymbol)` -- the receiver's type. */
    const val N_OTI_TYPEOF = 22
    /** [R_OT_IDENT]: the `exprType` derivation and class-typed handling below it. */
    const val N_OTI_TAIL = 23
    /** [R_OT_IDENT]: the `identSymbol == null` fallback branch. */
    const val N_OTI_ELSE = 24

    // Round 792, (ENGINE.2e): pricing a candidate WHOLE-FUNCTION pre-gate. The
    // three rows below are the only way to answer "what is behind the population
    // this gate would skip" with a MEASURE rather than a count -- and the count
    // is what every over-estimate in this codebase was built from.

    /** The candidate pre-gate's OWN cost, at every call. */
    const val N_PG_GATE = 25
    /** `checkMemberAccessMissingCore` for the calls the gate would SKIP -- the prize. */
    const val N_PG_CORE_PASS = 26
    /** `checkMemberAccessMissingCore` for the calls it would not -- the residue. */
    const val N_PG_CORE_FAIL = 27

    /**
     * Round 794, (ENGINE.2f): [N_U_RETRY]'s complement -- the LOOP-FREE half, the
     * one the substitution serves. Split off so the prize is a MEASURE of its own
     * population rather than `49% x 66 ms`, which is exactly the
     * `count x mean-cost` extrapolation every over-estimate in this codebase came
     * from. Under `--verifyUnionRetry` the arm still re-walks, so the row prices
     * the PRE-change cost; in production it prices what is left of it.
     */
    const val N_U_RETRY_LF = 28

    const val NN = 29

    val nNames: Array<String> = arrayOf(
        "  of which cpaComputeArgCtxTypes (clean)",
        "  of which ... calls with a consumable argument",
        "  of which B464 getTypeOfExpression(recv)",
        "  of which B464 closureStarts scan",
        "  of which B464 narrowing FLOW WALK",
        "  R_FLOW b1: getTypeOfExpression(receiver)",
        "  R_FLOW b1: round-489 pre-gate (2 lookups)",
        "  R_FLOW b1: the PLAIN narrowing FLOW WALK",
        "  R_FLOW b2: base type + getFlowAt",
        "  R_FLOW b2: base-projection FLOW WALK",
        "  R_FLOW b3: this-receiver narrowing",
        "  R_FLOW b1: the loop-entry RETRY walk",
        "  (b) the DEFERRED suppression call (whole)",
        "  R_PRE: intersection-reduction never probe",
        "  R_IDENT b1: B589 clodule receiver scan",
        "  R_IDENT b2: B586 {}/Object receiver block",
        "  R_IDENT b3: enum-typed ident receiver",
        "  R_OT_UNION: the PLAIN narrowing FLOW WALK",
        "  R_OT_UNION: the RETRY, loop-crossing half",
        "  R_OT_UNION: the TS2339 union elaboration",
        "  R_OT_IDENTSYM: lookupPerFileForNode",
        "  R_OT_IDENT: the specialised emission gates",
        "  R_OT_IDENT: getTypeOfSymbol(identSymbol)",
        "  R_OT_IDENT: exprType derivation + class gates",
        "  R_OT_IDENT: the identSymbol == null branch",
        "  PRE-GATE: the gate's own cost",
        "  PRE-GATE: the body for calls it would SKIP",
        "  PRE-GATE: the body for calls it would keep",
        "  R_OT_UNION: the RETRY, loop-free half (subst)",
    )

    var nNanos: LongArray = LongArray(NN)
    var nCalls: LongArray = LongArray(NN)

    /** Start a nested sub-measure, or 0 when not timing. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun t(): Long = if (mode == ON) PassTiming.nowNanos() else 0L

    /** Close a nested sub-measure opened at [t0], returning its duration. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun closeN(sec: Int, t0: Long): Long {
        if (mode != ON) return 0L
        val d = PassTiming.nowNanos() - t0
        nNanos[sec] += d; nCalls[sec]++
        return d
    }

    /** Charge an already-measured duration [d] to [sec] as well. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun addN(sec: Int, d: Long) {
        if (mode != ON) return
        nNanos[sec] += d; nCalls[sec]++
    }

    /**
     * Count one `cpaComputeArgCtxTypes` call and, when [consumable] says its
     * arguments can read a contextual type, charge its already-measured duration
     * [d] to [N_ARGCTX_CONSUMABLE] too. [consumable] is a lambda so the predicate
     * — itself a subtree walk — is never evaluated in production.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun noteArgCtx(d: Long, consumable: () -> Boolean) {
        if (mode == OFF) return
        argCtxCalls++
        if (consumable()) {
            argCtxConsumableCalls++
            if (mode == ON) { nNanos[N_ARGCTX_CONSUMABLE] += d; nCalls[N_ARGCTX_CONSUMABLE]++ }
        }
    }

    /** Count one B464 invocation reaching past the round-489 raw-type pre-gate. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun noteB464Reached() { if (mode != OFF) b464Reached++ }

    /** Count one B464 invocation that launches the narrowing flow walk. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun noteB464Walked() { if (mode != OFF) b464Walked++ }

    /**
     * (ENGINE.2d)(a) round 790 — `--verifyLoopRetry`. Keeps the PRE-GATE
     * behaviour (the round-425 loop-entry retry runs at every call and is
     * honoured) while [retryVerified] / [retryVerifyTypeDiff] /
     * [retryVerifyVerdictDiff] count how often the skip WOULD have changed the
     * answer. Round 788's protocol: falsify the hazard by measurement, not by
     * inspection. Independent of [mode], so the verification run carries no
     * timing-probe footprint. **A `retryVerifyVerdictDiff` above 0 falsifies
     * the equivalence outright.**
     */
    var verifyLoopRetry: Boolean = false

    /**
     * (ENGINE.2d)(a) round 790 — the CONTROL for [verifyLoopRetry]. Records the
     * comparison for EVERY retry call rather than only the skippable ones, so
     * the population it covers is exactly the one where the plain walk DID
     * arrive at a `FlowLoopLabel` (or truncated, or never ran). A non-zero
     * `typeDiff` here is what makes the skippable population's zero mean
     * something: it proves the instrument fires, and that the loop-label
     * observation is the signal that separates the two populations. Implies
     * [verifyLoopRetry].
     */
    var verifyLoopRetryAll: Boolean = false

    /** Calls whose plain walk provably made the retry redundant — the YIELD. */
    var retrySkippable: Long = 0
    /** Of those, the ones the verifier actually re-ran the retry for. */
    var retryVerified: Long = 0
    /** Of [retryVerified], those whose retry returned a DIFFERENT Type instance. */
    var retryVerifyTypeDiff: Long = 0
    /** Of [retryVerified], those whose retry SUPPRESSED where the plain did not. */
    var retryVerifyVerdictDiff: Long = 0

    /** Count one call the round-425 retry is skipped for. Gated like every other
     *  counter here, so a production call is a static read and a not-taken branch
     *  and no worker thread ever writes this shared object. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun noteRetrySkippable() { if (mode != OFF || verifyLoopRetry) retrySkippable++ }

    /** Record one `--verifyLoopRetry` comparison. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun noteRetryVerified(typeDiff: Boolean, verdictDiff: Boolean) {
        retryVerified++
        if (typeDiff) retryVerifyTypeDiff++
        if (verdictDiff) retryVerifyVerdictDiff++
    }

    /**
     * Round 792, (ENGINE.2e) — the round-424 UNION loop-entry retry, the second
     * instance of the shape (ENGINE.2d)(a) gated in the flow-suppression block.
     * [unionRetryCalls] is the population that reaches it; [unionRetryLoopFree]
     * the sub-population whose plain walk provably made it a pure REPEAT (the
     * round-790 bracket: a real traversal that arrived at no `FlowLoopLabel` and
     * truncated nowhere). Counted BEFORE anything is changed, because a lever
     * has to be priced before it is designed.
     */
    var unionRetryCalls: Long = 0
    /** Of [unionRetryCalls], the ones whose retry is a provable pure repeat. */
    var unionRetryLoopFree: Long = 0

    /** Count one round-424 union retry and whether it was provably redundant. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun noteUnionRetry(loopFree: Boolean) {
        if (mode == OFF && !verifyUnionRetry) return
        unionRetryCalls++
        if (loopFree) unionRetryLoopFree++
    }

    /**
     * Round 794, (ENGINE.2f) — `--verifyUnionRetry`. The round-424 UNION
     * loop-entry retry is SUBSTITUTED, not skipped: when the plain walk provably
     * arrived at no `FlowLoopLabel` and truncated nowhere, the follow-loop-entry
     * mirror makes the identical traversal, so its result is the plain walk's and
     * the retry is a pure repeat. Skipping the block instead would NOT be
     * equivalent — its first test is the IDENTITY `loopNarrowed !== raw`, and a
     * loop-free repeat crossing a branch join mints a FRESH equal union
     * (`getUnionType` does not intern), so the block does run and can suppress.
     *
     * Under this flag the retry still WALKS and its verdict is HONOURED — the run
     * therefore reproduces the pre-change binary byte for byte, which is what
     * makes it the grid baseline — and the substituted candidate is compared
     * against the re-walked one at three granularities: `Type` INSTANCE, union
     * MEMBER-ID SET, and the consumer's own suppression VERDICT.
     */
    var verifyUnionRetry: Boolean = false

    /**
     * The positive CONTROL for [verifyUnionRetry] (`--verifyUnionRetryAll`):
     * run the same comparison over the COMPLEMENT population as well — the
     * loop-CROSSING calls the substitution never serves, where round 424's whole
     * reason for existing says the two walks must disagree. Round 790's law: a
     * verifier that reads 0 both when the substitution is sound and when the
     * instrument is dead proves nothing, and the complement costs nothing.
     */
    var verifyUnionRetryAll: Boolean = false

    /** Comparisons performed under [verifyUnionRetry]. */
    var unionRetryVerified: Long = 0
    /** Of those, the substituted and re-walked types were different INSTANCES. */
    var unionRetryVerifyInstanceDiff: Long = 0
    /** Of those, their union MEMBER-ID SETS differed. */
    var unionRetryVerifyMemberDiff: Long = 0
    /** Of those, the consumer's suppression VERDICT differed. THE FALSIFIER. */
    var unionRetryVerifyVerdictDiff: Long = 0
    /** Retries that SUPPRESSED, split by whether the plain walk was loop-free —
     *  i.e. how much observable work the substitution is actually responsible for. */
    var unionRetrySuppressedLoopFree: Long = 0
    var unionRetrySuppressedOther: Long = 0

    /** Record one `--verifyUnionRetry` comparison. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun noteUnionRetryVerified(instanceDiff: Boolean, memberDiff: Boolean, verdictDiff: Boolean) {
        unionRetryVerified++
        if (instanceDiff) unionRetryVerifyInstanceDiff++
        if (memberDiff) unionRetryVerifyMemberDiff++
        if (verdictDiff) unionRetryVerifyVerdictDiff++
    }

    /** Record that a retry suppressed, split by the loop-free bracket's verdict. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun noteUnionRetrySuppressed(loopFree: Boolean) {
        if (mode == OFF && !verifyUnionRetry) return
        if (loopFree) unionRetrySuppressedLoopFree++ else unionRetrySuppressedOther++
    }

    /**
     * Round 792, (ENGINE.2e) — `--cmamPreGate`. Prices a candidate
     * WHOLE-FUNCTION pre-gate for `checkMemberAccessMissing`: "the property
     * already resolves on the receiver's own (apparent) type, so nothing this
     * function can say is true". It HONOURS NOTHING — the body always runs and
     * the compiler's output is unchanged — and reports three things the design
     * decision needs: the population it would skip, the body time BEHIND that
     * population (a measure, not a count), and [preGatePassEmitted], the number
     * of skipped calls whose body actually appended a diagnostic. **The last is
     * the falsifier: a non-zero there is a lost diagnostic per unit.**
     */
    var preGateProbe: Boolean = false

    /**
     * The positive CONTROL for [preGateProbe] (`--cmamPreGateBogus`): the gate
     * answers "yes" for EVERY call, so the skip set becomes the whole population
     * and [preGatePassEmitted] must then equal the number of calls that emit.
     * A zero under this flag would mean the falsifier column cannot see an
     * emission at all — CLAUDE.md's round-765 rule, a counter reporting 0 with no
     * control is a dead instrument.
     */
    var preGateBogus: Boolean = false

    /** Calls whose property already resolves on the receiver — the SKIP set. */
    var preGatePass: Long = 0
    /** Calls where it does not. */
    var preGateFail: Long = 0
    /** Of [preGatePass], those whose body appended a diagnostic. THE FALSIFIER. */
    var preGatePassEmitted: Long = 0
    /** Of [preGateFail], those whose body appended one — the instrument's control:
     *  a zero here would mean the emission detector never fires at all. */
    var preGateFailEmitted: Long = 0

    /** Record one pre-gate observation. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun notePreGate(pass: Boolean, emitted: Boolean) {
        if (pass) { preGatePass++; if (emitted) preGatePassEmitted++ }
        else { preGateFail++; if (emitted) preGateFailEmitted++ }
    }

    /**
     * (ENGINE.2d)(b) round 791 — `--verifyDeferSuppression`. Evaluates
     * `checkMemberAccessMissing`'s flow-suppression predicate TWICE per call:
     * EAGERLY, at the position the three blocks used to occupy, and again
     * DEFERRED, after the body has run — then compares. The eager verdict is the
     * one honoured, so the verify run's diagnostic output equals the PRE-change
     * binary's by construction and the two halves of the claim are independent:
     * a byte-identical `--listAll`, and [deferVerifyVerdictDiff] == 0.
     *
     * **The only thing the deferral can change is what this measures.** The
     * retraction is complete by construction (the body's sole mutation is an
     * append to `diagnostics`), so the residual risk is cache-mutation ORDER —
     * the body now runs BETWEEN the two evaluations. A non-zero
     * [deferVerifyVerdictDiff] falsifies the deferral outright.
     */
    var verifyDeferSuppression: Boolean = false

    /**
     * (ENGINE.2d)(b) round 791 — the positive CONTROL for
     * [verifyDeferSuppression]: the deferred evaluation is handed a property
     * name nothing can resolve, which makes the round-489 pre-gate pass
     * everywhere and the suppression answer false everywhere. A live comparator
     * MUST then report a large [deferVerifyVerdictDiff]; a zero here would mean
     * the instrument is dead and the real run's zero says nothing. Implies
     * [verifyDeferSuppression].
     */
    var verifyDeferSuppressionBogus: Boolean = false

    /** Calls on which the deferred predicate was evaluated at all — in production
     *  the yield (only a call whose body EMITTED reaches it), under the verifier
     *  every call that reached the blocks' old position. */
    var deferEvaluated: Long = 0
    /** Of those, the ones whose body appended a diagnostic. */
    var deferEmitted: Long = 0
    /** Of those, the ones the deferred predicate SUPPRESSED (a retraction). */
    var deferSuppressed: Long = 0
    /** `--verifyDeferSuppression` comparisons performed. */
    var deferVerified: Long = 0
    /** Of [deferVerified], those whose two evaluations narrowed to a DIFFERENT
     *  `Type` instance — a granularity finer than the verdict. */
    var deferVerifyTypeDiff: Long = 0
    /** Of [deferVerified], those whose two evaluations DISAGREED. Non-zero
     *  falsifies the deferral. */
    var deferVerifyVerdictDiff: Long = 0

    /** Record one deferred evaluation. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun noteDeferEvaluated(emitted: Boolean, suppressed: Boolean) {
        if (mode == OFF && !verifyDeferSuppression) return
        deferEvaluated++
        if (emitted) deferEmitted++
        if (suppressed) deferSuppressed++
    }

    /** Record one `--verifyDeferSuppression` comparison. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun noteDeferVerified(typeDiff: Boolean, verdictDiff: Boolean) {
        deferVerified++
        if (typeDiff) deferVerifyTypeDiff++
        if (verdictDiff) deferVerifyVerdictDiff++
    }

    /** `cpaComputeArgCtxTypes` invocations. */
    var argCtxCalls: Long = 0
    /** Of those, the ones whose argument subtrees can CONSUME a contextual type. */
    var argCtxConsumableCalls: Long = 0
    /** B464 invocations reaching past the round-489 raw-type pre-gate. */
    var b464Reached: Long = 0
    /** Of those, the ones that launch the narrowing flow walk. */
    var b464Walked: Long = 0

    /** Level P's window: open only across `cpaSpineLeave`'s anchor blocks. */
    var inCpa: Boolean = false
    var curP: Int = -1
    var curTP: Long = 0
    var depthP: Int = 0
    var curQ: Int = -1
    var curTQ: Long = 0
    var depthQ: Int = 0
    var curR: Int = -1
    var curTR: Long = 0
    var depthR: Int = 0

    /** [beginP]'s "not measuring" sentinel — distinct from "no caller row" (-1). */
    const val P_INACTIVE = -2

    fun reset() {
        pNanos = LongArray(NP); pCalls = LongArray(NP); pArm = LongArray(NPA)
        nNanos = LongArray(NN); nCalls = LongArray(NN)
        argCtxCalls = 0; argCtxConsumableCalls = 0; b464Reached = 0; b464Walked = 0
        retrySkippable = 0; retryVerified = 0
        retryVerifyTypeDiff = 0; retryVerifyVerdictDiff = 0
        deferEvaluated = 0; deferEmitted = 0; deferSuppressed = 0
        deferVerified = 0; deferVerifyTypeDiff = 0; deferVerifyVerdictDiff = 0
        unionRetryCalls = 0; unionRetryLoopFree = 0
        unionRetryVerified = 0; unionRetryVerifyInstanceDiff = 0
        unionRetryVerifyMemberDiff = 0; unionRetryVerifyVerdictDiff = 0
        unionRetrySuppressedLoopFree = 0; unionRetrySuppressedOther = 0
        preGatePass = 0; preGateFail = 0
        preGatePassEmitted = 0; preGateFailEmitted = 0
        qNanos = LongArray(NQ); qCalls = LongArray(NQ); qExitIn = LongArray(NQ)
        rNanos = LongArray(NR); rCalls = LongArray(NR); rExitIn = LongArray(NR)
        rExitWalk = LongArray(NR); rWalked = false
        invocationsP = 0; outermostP = 0; maxDepthP = 0; invocationsPOutside = 0
        invocationsQ = 0; invocationsQNested = 0
        invocationsR = 0; invocationsRNested = 0
        distinctPa = HashSet(); distinctP = HashSet()
        inCpa = false
        curP = -1; curTP = 0; depthP = 0
        curQ = -1; curTQ = 0; depthQ = 0
        curR = -1; curTR = 0; depthR = 0
    }

    // The entry points are `inline` so a production call is a static read plus a
    // not-taken branch rather than a call, matching [ArgSections] and [CtaSections].

    /** Open level P's window — one of `cpaSpineLeave`'s anchor blocks. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun enterCpa() { if (mode != OFF) inCpa = true }

    /** Close level P's window. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun exitCpa() { if (mode != OFF) inCpa = false }

    /**
     * Open level P's partition for one `checkPropertyAccessInExpr` invocation,
     * CLOSING the caller's running row and returning it so [endP] can reopen it.
     * Returns [P_INACTIVE] when this invocation is outside the [inCpa] window (or
     * the probe is off), in which case [endP] must do nothing.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun beginP(fileName: String, nodeId: Int): Int {
        if (mode == OFF) return P_INACTIVE
        if (!inCpa) { invocationsPOutside++; return P_INACTIVE }
        invocationsP++
        if (depthP == 0) outermostP++
        depthP++
        if (depthP > maxDepthP) maxDepthP = depthP
        if (mode == CENSUS) { if (nodeId >= 0) distinctP.add(nodeKey(fileName, nodeId)); return -1 }
        val prev = curP
        val now = PassTiming.nowNanos()
        if (prev >= 0) { pNanos[prev] += now - curTP; pCalls[prev]++ }
        curP = P_ENTRY
        curTP = now
        return prev
    }

    /** Close level P's running row and start [sec]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun atP(sec: Int) {
        if (mode != ON || curP < 0) return
        val now = PassTiming.nowNanos()
        pNanos[curP] += now - curTP
        pCalls[curP]++
        curP = sec
        curTP = now
    }

    /** Close this invocation's running row and reopen the caller's, [prev]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun endP(prev: Int) {
        if (mode == OFF || prev == P_INACTIVE) return
        if (mode == CENSUS) { depthP--; return }
        val now = PassTiming.nowNanos()
        if (curP >= 0) { pNanos[curP] += now - curTP; pCalls[curP]++ }
        depthP--
        curP = prev
        curTP = now
    }

    /** Count one `when` arm of `checkPropertyAccessInExpr`. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun armP(arm: Int) {
        if (mode == OFF || !inCpa) return
        pArm[arm]++
    }

    /** Open level Q's partition for one `checkSinglePropertyAccess`. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun beginQ(fileName: String, nodeId: Int) {
        if (mode == OFF) return
        depthQ++
        if (depthQ != 1) { invocationsQNested++; return }
        invocationsQ++
        if (mode == CENSUS) { if (nodeId >= 0) distinctPa.add(nodeKey(fileName, nodeId)); return }
        curQ = Q_ENTRY
        curTQ = PassTiming.nowNanos()
    }

    /** Close level Q's running section and start [sec]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun atQ(sec: Int) {
        if (mode != ON || depthQ != 1) return
        val now = PassTiming.nowNanos()
        qNanos[curQ] += now - curTQ
        qCalls[curQ]++
        curQ = sec
        curTQ = now
    }

    /** Close whatever level-Q section is open, recording the exit row. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun endQ() {
        if (mode == OFF) return
        if (mode != CENSUS && depthQ == 1 && curQ >= 0) {
            qNanos[curQ] += PassTiming.nowNanos() - curTQ
            qCalls[curQ]++
            qExitIn[curQ]++
            curQ = -1
        }
        depthQ--
    }

    /** Open level R's partition for one `checkMemberAccessMissing`. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun beginR() {
        if (mode == OFF) return
        depthR++
        if (depthR != 1) { invocationsRNested++; return }
        invocationsR++
        rWalked = false
        if (mode == CENSUS) return
        curR = R_ENTRY
        curTR = PassTiming.nowNanos()
    }

    /** Mark this invocation as one that launched an [R_FLOW] block-1 flow walk. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun noteRWalk() { if (mode != OFF) rWalked = true }

    /** Close level R's running section and start [sec]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun atR(sec: Int) {
        if (mode != ON || depthR != 1) return
        val now = PassTiming.nowNanos()
        rNanos[curR] += now - curTR
        rCalls[curR]++
        curR = sec
        curTR = now
    }

    /**
     * Close whatever level-R section is open, recording the EXIT row — which is
     * the census that answers "how far does a typical call get", and the only way
     * to see a gate that is cheap to evaluate but placed after expensive work.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun endR() {
        if (mode == OFF) return
        if (mode != CENSUS && depthR == 1 && curR >= 0) {
            rNanos[curR] += PassTiming.nowNanos() - curTR
            rCalls[curR]++
            rExitIn[curR]++
            if (rWalked) rExitWalk[curR]++
            curR = -1
        }
        depthR--
    }

    private fun ms(n: Long): String = (n / 1_000_000).toString()

    fun report(): String = buildString {
        appendLine("== (ENGINE.2) property-access attribution: checkPropertyAccessInExpr + checkSinglePropertyAccess ==")
        appendLine(
            "mode=${when (mode) { ON -> "ON"; COARSE -> "COARSE"; CENSUS -> "CENSUS"; else -> "OFF" }}" +
                "  level P: $invocationsP invocations in-window (${outermostP} outermost, max depth $maxDepthP)" +
                ", $invocationsPOutside outside" +
                "  level Q: $invocationsQ ($invocationsQNested nested)"
        )
        if (mode == CENSUS) {
            val dp = distinctP.size
            val dq = distinctPa.size
            appendLine(
                "G4 distinct-node census: level P $invocationsP visits / $dp distinct nodes = " +
                    "${if (dp > 0) invocationsP * 100 / dp else 0}/100 ; " +
                    "level Q $invocationsQ visits / $dq distinct PropertyAccessExpression nodes = " +
                    "${if (dq > 0) invocationsQ * 100 / dq else 0}/100"
            )
        }
        var pTotal = 0L
        for (s in 0 until NP) pTotal += pNanos[s]
        if (pTotal > 0) {
            appendLine("level P total: ${ms(pTotal)} ms (compare `--spineSections` cpa anchor + owner rows)")
            for (s in 0 until NP) {
                if (pCalls[s] == 0L && pNanos[s] == 0L) continue
                val pct = pNanos[s] * 1000 / pTotal
                appendLine(
                    "  ${pNames[s].padEnd(48)} ${ms(pNanos[s]).padStart(6)} ms " +
                        "(${(pct / 10).toString().padStart(3)}.${pct % 10}%) over ${pCalls[s].toString().padStart(9)} closes"
                )
            }
        }
        var qTotal = 0L
        for (s in 0 until NQ) qTotal += qNanos[s]
        if (qTotal > 0) {
            appendLine("level Q total: ${ms(qTotal)} ms (compare level P's checkSinglePropertyAccess row)")
            for (s in 0 until NQ) {
                if (qCalls[s] == 0L && qNanos[s] == 0L) continue
                val pct = qNanos[s] * 1000 / qTotal
                appendLine(
                    "  ${qNames[s].padEnd(48)} ${ms(qNanos[s]).padStart(6)} ms " +
                        "(${(pct / 10).toString().padStart(3)}.${pct % 10}%) over ${qCalls[s].toString().padStart(9)} closes" +
                        ", exits ${qExitIn[s]}"
                )
            }
        }
        var rTotal = 0L
        for (s in 0 until NR) rTotal += rNanos[s]
        if (rTotal > 0) {
            appendLine(
                "level R total: ${ms(rTotal)} ms over $invocationsR invocations " +
                    "($invocationsRNested nested) — compare level Q's checkMemberAccessMissing row"
            )
            for (s in 0 until NR) {
                if (rCalls[s] == 0L && rNanos[s] == 0L) continue
                val pct = rNanos[s] * 1000 / rTotal
                appendLine(
                    "  ${rNames[s].padEnd(48)} ${ms(rNanos[s]).padStart(6)} ms " +
                        "(${(pct / 10).toString().padStart(3)}.${pct % 10}%) over ${rCalls[s].toString().padStart(9)} closes" +
                        ", exits ${rExitIn[s]} (of which walkers ${rExitWalk[s]})"
                )
            }
        }
        if (retrySkippable > 0 || retryVerified > 0) {
            appendLine(
                "(ENGINE.2d)(a) round-425 retry: skippable $retrySkippable" +
                    (if (verifyLoopRetry)
                        " | VERIFIED $retryVerified" +
                            (if (verifyLoopRetryAll) " (ALL — the control)" else "") +
                            ", type-diff $retryVerifyTypeDiff, " +
                            "VERDICT-DIFF $retryVerifyVerdictDiff"
                    else " (verifier off)")
            )
        }
        if (deferEvaluated > 0 || deferVerified > 0) {
            appendLine(
                "(ENGINE.2d)(b) deferred suppression: evaluated $deferEvaluated" +
                    ", body-emitted $deferEmitted, RETRACTED $deferSuppressed" +
                    (if (verifyDeferSuppression)
                        " | VERIFIED $deferVerified" +
                            (if (verifyDeferSuppressionBogus) " (BOGUS — the control)" else "") +
                            ", type-diff $deferVerifyTypeDiff, " +
                            "VERDICT-DIFF $deferVerifyVerdictDiff"
                    else " (verifier off)")
            )
        }
        if (unionRetryCalls > 0) {
            appendLine(
                "(ENGINE.2e) round-424 UNION retry: reached $unionRetryCalls, " +
                    "provably redundant (loop-free plain walk) $unionRetryLoopFree " +
                    "(${unionRetryLoopFree * 1000 / unionRetryCalls / 10}%)" +
                    " | SUPPRESSED loop-free $unionRetrySuppressedLoopFree, " +
                    "loop-crossing $unionRetrySuppressedOther"
            )
            appendLine(
                "(ENGINE.2f) UNION retry SUBSTITUTION: " +
                    (if (verifyUnionRetry)
                        "VERIFIED $unionRetryVerified" +
                            (if (verifyUnionRetryAll) " (ALL — the complement control)" else "") +
                            ", instance-diff $unionRetryVerifyInstanceDiff" +
                            ", member-diff $unionRetryVerifyMemberDiff" +
                            ", VERDICT-DIFF $unionRetryVerifyVerdictDiff"
                    else "live (verifier off)")
            )
        }
        if (preGatePass > 0 || preGateFail > 0) {
            val tot = preGatePass + preGateFail
            appendLine(
                "(ENGINE.2e) PRE-GATE" + (if (preGateBogus) " (BOGUS — the control)" else "") +
                    ": would skip $preGatePass of $tot " +
                    "(${preGatePass * 1000 / tot / 10}%) | of those, bodies that EMITTED: " +
                    "$preGatePassEmitted   (kept calls that emitted " +
                    "$preGateFailEmitted)"
            )
        }
        if (argCtxCalls > 0 || b464Reached > 0) {
            appendLine(
                "populations: cpaComputeArgCtxTypes $argCtxCalls calls, " +
                    "$argCtxConsumableCalls with a CONSUMABLE argument " +
                    "(${if (argCtxCalls > 0) argCtxConsumableCalls * 1000 / argCtxCalls / 10 else 0}%)" +
                    "   B464 reached $b464Reached, flow-walked $b464Walked"
            )
        }
        var nAny = false
        for (s in 0 until NN) if (nCalls[s] != 0L) nAny = true
        if (nAny) {
            appendLine("nested sub-measures (INSIDE the rows above):")
            for (s in 0 until NN) {
                if (nCalls[s] == 0L) continue
                appendLine(
                    "  ${nNames[s].trim().padEnd(48)} ${ms(nNanos[s]).padStart(6)} ms " +
                        "over ${nCalls[s].toString().padStart(9)} calls"
                )
            }
        }
        var armTotal = 0L
        for (a in 0 until NPA) armTotal += pArm[a]
        if (armTotal > 0) {
            appendLine("level P arm census (arms TAKEN — never a population): $armTotal")
            for (a in 0 until NPA) {
                if (pArm[a] == 0L) continue
                appendLine("  ${pArmNames[a].padEnd(48)} ${pArm[a].toString().padStart(9)}")
            }
        }
    }

    fun csv(): String = buildString {
        appendLine("level,section,closes,nanos")
        for (s in 0 until NP) {
            if (pCalls[s] == 0L && pNanos[s] == 0L) continue
            appendLine("P,\"${pNames[s].trim()}\",${pCalls[s]},${pNanos[s]}")
        }
        for (s in 0 until NQ) {
            if (qCalls[s] == 0L && qNanos[s] == 0L) continue
            appendLine("Q,\"${qNames[s].trim()}\",${qCalls[s]},${qNanos[s]}")
        }
        for (s in 0 until NR) {
            if (rCalls[s] == 0L && rNanos[s] == 0L) continue
            appendLine("R,\"${rNames[s].trim()}\",${rCalls[s]},${rNanos[s]}")
        }
        for (s in 0 until NR) {
            if (rExitIn[s] == 0L) continue
            appendLine("REXIT,\"${rNames[s].trim()}\",${rExitIn[s]},${rExitWalk[s]}")
        }
        for (s in 0 until NN) {
            if (nCalls[s] == 0L) continue
            appendLine("N,\"${nNames[s].trim()}\",${nCalls[s]},${nNanos[s]}")
        }
        for (a in 0 until NPA) {
            if (pArm[a] == 0L) continue
            appendLine("ARM,\"${pArmNames[a].trim()}\",${pArm[a]},0")
        }
        if (retrySkippable > 0 || retryVerified > 0) {
            appendLine("RETRY,\"skippable\",$retrySkippable,0")
            appendLine("RETRY,\"verified\",$retryVerified,0")
        }
        if (deferEvaluated > 0 || deferVerified > 0) {
            appendLine("DEFER,\"evaluated\",$deferEvaluated,0")
            appendLine("DEFER,\"emitted\",$deferEmitted,0")
            appendLine("DEFER,\"retracted\",$deferSuppressed,0")
            appendLine("DEFER,\"verifyVerdictDiff\",$deferVerifyVerdictDiff,0")
            appendLine("RETRY,\"typeDiff\",$retryVerifyTypeDiff,0")
            appendLine("RETRY,\"verdictDiff\",$retryVerifyVerdictDiff,0")
        }
        if (unionRetryCalls > 0) {
            appendLine("URETRY,\"reached\",$unionRetryCalls,0")
            appendLine("URETRY,\"loopFree\",$unionRetryLoopFree,0")
            appendLine("URETRY,\"suppressedLoopFree\",$unionRetrySuppressedLoopFree,0")
            appendLine("URETRY,\"suppressedOther\",$unionRetrySuppressedOther,0")
            appendLine("URETRY,\"verified\",$unionRetryVerified,0")
            appendLine("URETRY,\"instanceDiff\",$unionRetryVerifyInstanceDiff,0")
            appendLine("URETRY,\"memberDiff\",$unionRetryVerifyMemberDiff,0")
            appendLine("URETRY,\"verdictDiff\",$unionRetryVerifyVerdictDiff,0")
        }
    }
}
