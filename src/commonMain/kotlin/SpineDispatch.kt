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

    /** The wrapper's own transition. Probe-only; absent in production. */
    const val ENTRY = 28

    /** The FIRST empty boundary span of an invocation — not steady state. */
    const val OVERHEAD_FIRST = 29

    /** In-situ steady-state empty boundaries; a pessimistic upper bound (round 734). */
    const val OVERHEAD = 30

    const val N = 31

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
        cur = ENTRY
        curT = PassTiming.nowNanos()
    }

    /** Close the running section and start [sec]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun at(sec: Int) {
        if (mode == OFF || depth != 1) return
        if (mode == COARSE && !coarseAnchor[sec]) return
        val now = PassTiming.nowNanos()
        nanos[cur] += now - curT
        calls[cur]++
        cur = sec
        curT = now
    }

    /** Count one loop iteration (free of any timestamp). */
    @Suppress("NOTHING_TO_INLINE")
    inline fun iteration() {
        if (mode == OFF || depth != 1) return
        iterations++
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

    /** Opt-in; [OFF] in production. Set by `--narrowSections{,Coarse}`. */
    var mode: Int = OFF

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
        if (mode == ON) { nanos[S_WALK] += took; calls[S_WALK]++ }
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

    /** Close one `applyConditionNarrowing`, flagging an identity result. */
    fun closeCond(t0: Long, identity: Boolean) {
        condCalls++
        if (mode == ON) {
            val d = PassTiming.nowNanos() - t0
            nanos[S_COND] += d; calls[S_COND]++
            if (identity) { condIdentity++; condIdentityNanos += d }
        } else if (identity) condIdentity++
    }

    /** Start a nested sub-measure, or 0 when not in [ON]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun t(): Long = if (mode == ON) PassTiming.nowNanos() else 0L

    /** Close a nested sub-measure opened at [t0]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun close(sec: Int, t0: Long) {
        if (mode != ON) return
        nanos[sec] += PassTiming.nowNanos() - t0
        calls[sec]++
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
        if (mode == ON) {
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
        }
    }

    fun csv(): String = buildString {
        appendLine("section,reached,nanos")
        for (s in 0 until N) {
            if (calls[s] == 0L) continue
            appendLine("\"${names[s].trim()}\",${calls[s]},${nanos[s]}")
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
    }

    /** Machine-readable dump: one line per section. */
    fun csv(): String = buildString {
        appendLine("section,reached,nanos,exitedIn")
        for (s in 0 until N) {
            if (calls[s] == 0L) continue
            appendLine("\"${names[s].trim()}\",${calls[s]},${nanos[s]},${exitIn[s]}")
        }
    }
}
