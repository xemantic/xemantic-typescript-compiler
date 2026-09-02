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
 * (INV.1) The process-global MODE that turns the per-file [NodeAnswerStore] on
 * for every checker constructed while it is set — the CLI's `--nodeAnswers`,
 * through the round-848 [ModeLedger] like every other mode here.
 *
 * The store itself is threaded into [Checker] as a constructor parameter,
 * because it is DATA the way a [TypeCaptureRequest] and a [CheckedNodeSink]
 * are; this object exists so that a CLI measurement (`cost_gate.py`,
 * `BenchMain`) can arm it without a parameter reaching every construction site
 * in `TypeScriptCompiler`. A checker reads it ONCE, at construction, as the
 * default of its own parameter — never per node.
 *
 * OFF by default, and off is the whole compiler (`docs/INVERSION-DESIGN.md`
 * § 9 / § 10): with the mode off no store is allocated, no type is computed
 * for the store's sake, and `NodeAnswerStoreTest` pins the computation count
 * at exactly zero on a production-mode compile — round 900's law, that a
 * guard cannot protect its own argument, is why the count is a pin and not a
 * KDoc claim.
 */
object NodeAnswers {

    /** Arm recording for every checker constructed while this is true. */
    var enabled: Boolean = false

    /**
     * (INV.2) MEASUREMENT SEAM: which of the store's three companion channels a
     * checker constructed while this is set records beside the type — a bit
     * set of [SYMBOLS], [CALLS] and [CONTEXTUAL], [ALL] by default. It exists so
     * the flag-on recording cost can be ATTRIBUTED one channel at a time
     * (`BenchMain`'s `nodeAnswers:<channel>` arms; design § 9b) and for nothing
     * else: a store recorded under a partial mask cannot serve an oracle, and
     * [Checker.typeOracle] refuses one. Read ONCE per checker, at construction,
     * like [enabled].
     */
    var channels: Int = ALL

    const val SYMBOLS: Int = 1
    const val CALLS: Int = 2
    const val CONTEXTUAL: Int = 4

    /**
     * (INV.1b) The TYPE channel itself. With this bit CLEAR the store records
     * `anyType` at every expression WITHOUT resolving it — the arm that prices
     * the per-node ambient RECONSTRUCTION alone, so `types − reconstruction`
     * is the resolution. A store recorded that way answers nothing true and
     * [Checker.typeOracle] refuses it like every other partial mask.
     */
    const val TYPES: Int = 8
    const val ALL: Int = TYPES or SYMBOLS or CALLS or CONTEXTUAL

    /**
     * Counters, for the CLI report line. Added to ONCE per checker after its
     * spine walk, on the caller's thread; cleared by [reset] after the report
     * prints, exactly as [PassTiming]'s counters are. Not owned by the ledger,
     * for the reason its KDoc gives.
     */
    var recordedTotal: Long = 0
    var filesTotal: Int = 0

    /** (INV.2) The three companion channels' totals, for the same report line. */
    var symbolsTotal: Long = 0
    var callsTotal: Long = 0
    var contextualTotal: Long = 0

    fun reset() {
        recordedTotal = 0
        filesTotal = 0
        symbolsTotal = 0
        callsTotal = 0
        contextualTotal = 0
    }
}

/**
 * (INV.2) What overload resolution picked at ONE call-like node, recorded as the
 * walk passed it — tsgo's `getResolvedSignature` answer, plus the one fact that
 * separates its three ways of being null: [candidates] is how many signatures
 * were on offer, so `0` means the callee is not callable at all, while a null
 * [signature] beside a positive count means none of them accepted the arguments
 * (the shape TS2769 reports).
 */
class ResolvedCall(
    /** The signature overload resolution chose, or null when it chose none. */
    val signature: Signature?,
    /** How many signatures were on offer — `0` means the callee is not callable. */
    val candidates: Int,
)

/**
 * (INV.1) Stage 1 of `docs/INVERSION-DESIGN.md`: the walk's own expression-type
 * answer for every [Expression] of ONE file, recorded AS THE SPINE WALKS PAST
 * IT and readable afterwards by node identity.
 *
 * ## Why it exists
 *
 * The checker's answers are functions of walk-scoped state (`currentLocalTypes`,
 * the cta frames, `currentFlowGraph`) that is at rest once the check is over,
 * so "hand the checker back and ask it later" answers a body local with a
 * same-named global's type and a parameter with `any` — `TypeCaptureMeasurementTest`
 * measures it. The two consumers that exist today ([TypeCaptureRequest] for a
 * caret, [CheckedNodeSink] for a backend) both work INWARDS for that reason.
 * This store is the third shape, and the one a post-hoc type oracle needs: the
 * SAME answer, taken under the SAME reconstructed ambient [Checker.typeCaptureVisit]
 * installs, for every expression, retained.
 *
 * ## Shape
 *
 * One array per file, indexed by `nodeId`. `nodeId` restarts at 0 in every
 * [SourceFile] (INV.2(a)), which round 787 met as the enemy of any PROGRAM-wide
 * id-keyed table and which is the friend of a per-file one: dense, no hashing,
 * and a (BIND.1)-class cross-file collision is unrepresentable by construction.
 * Sized by [SourceFile.nodeCount], which [indexSourceFile] stamps.
 *
 * The design (§ 4) wrote this as an `IntArray` of type ids resolved through an
 * id→Type lookup; no such lookup exists in this checker (`Type.id` is minted by
 * a thread-local counter and registered nowhere), so the slot holds the [Type]
 * itself. Under compressed references that is the same four bytes per slot,
 * and it removes a resolution step rather than adding one; interning means no
 * new `Type` objects are minted for the store's sake either way.
 *
 * ## The rule
 *
 * FIRST WINS, exactly as [Checker.typeCaptureRecord]'s: the hook that runs
 * under the tightest ambient runs first, and a later hook answering the same
 * node from a wider ambient must not overwrite it. [record] refuses a second
 * write and says so, and the refusal is checked BEFORE the type is computed
 * (see [Checker.nodeAnswerRecord]) so a refused write costs no resolution.
 *
 * What is recorded is [Checker.typeCaptureReportedType]'s answer — the type OF
 * THE ACCESS for a member name (BUG.4, tsc's own `getTypeOfSymbolAtLocation`
 * rule), the declared member type for a member declaration name (API.11), and
 * `getTypeOfExpression` for everything else — so that the store is the capture
 * generalised to every node, with no display rule of its own.
 *
 * Valid for ONE build: it holds `Type` objects of one checker, and every
 * id-keyed thing about them is per-build and per-thread (INV.6(6c0), (INC.46)).
 */
class NodeAnswerStore(val sourceFile: SourceFile) {

    private val types: Array<Type?> = arrayOfNulls(sourceFile.nodeCount)

    /**
     * (INV.2) The three COMPANION channels Stage 2 adds beside the type, each
     * keyed by the same `nodeId` and each written at most once, under the
     * type's own first-wins gate ([Checker.nodeAnswerRecord] records all four
     * from ONE visit, so a node that holds a type holds whatever companions the
     * walk could give it, and a node that holds none holds no companion either).
     *
     *  - [symbols]: what a NAME resolves to — an [Identifier] or a member-name
     *    literal (`o["p"]`) — as the walk's own scope chain and receiver tables
     *    answered it. The slot holds the [Symbol] directly for the common
     *    single answer and a `List<Symbol>` where a member is answered by several
     *    union constituents ((API.3d)'s collection rule, round 916); nothing is
     *    stored for a name that resolved to nothing, and [symbolsAt] reads both
     *    shapes back as a list. The `Any?` seam is deliberate and OFF the
     *    production path (the store exists only under the flag).
     *  - [calls]: the overload picked at a [CallExpression] / [NewExpression],
     *    sparse, so a primitive-keyed map rather than a third dense array.
     *  - [contextual]: the type that contextually types an expression where the
     *    checker computes one ((API.10)'s syntactic walk) — a dense array
     *    because the population is every argument, initializer and return.
     */
    private val symbols: Array<Any?> = arrayOfNulls(sourceFile.nodeCount)
    private val calls = IntKeyMap<ResolvedCall>(64)
    private val contextual: Array<Type?> = arrayOfNulls(sourceFile.nodeCount)

    /** How many nodes hold an answer — the receipt a cost measurement quotes. */
    var recorded: Int = 0
        private set

    /** (INV.2) How many name nodes hold a symbol answer. */
    var symbolsRecorded: Int = 0
        private set

    /** (INV.2) How many call-like nodes hold a resolved-call answer. */
    var callsRecorded: Int = 0
        private set

    /** (INV.2) How many expressions hold a contextual-type answer. */
    var contextualRecorded: Int = 0
        private set

    /** The slot count, i.e. the file's [SourceFile.nodeCount]. */
    val capacity: Int get() = types.size

    /**
     * The recorded type at [node], or null when nothing was recorded there —
     * including for a node this file never indexed (`nodeId` −1, i.e. a
     * synthesized or `copy()`-ed node, INV.2(a)) and for a node of ANOTHER file
     * whose id happens to be in range, which is why a reader must hold the
     * store OF THE NODE'S FILE; [Checker.nodeAnswers] is keyed by file name for
     * exactly that.
     */
    fun typeAt(node: Node): Type? {
        val id = (node as NodeBase).nodeId
        return if (id in types.indices) types[id] else null
    }

    /** Whether [node] already holds an answer — the pre-computation refusal. */
    fun has(node: Node): Boolean {
        val id = (node as NodeBase).nodeId
        return id in types.indices && types[id] != null
    }

    /**
     * Records [type] at [node] unless a type is already there. Returns whether
     * the write happened. An unindexed node (`nodeId` out of range) is refused
     * too: there is no slot it could own.
     */
    fun record(node: Node, type: Type): Boolean {
        val id = (node as NodeBase).nodeId
        if (id !in types.indices || types[id] != null) return false
        types[id] = type
        recorded++
        return true
    }

    /**
     * (INV.2) Records what the name [node] resolved to, unless a symbol answer is
     * already there or [resolved] is empty. Returns whether the write happened.
     * One symbol is stored bare, several as the list handed in.
     */
    fun recordSymbols(node: Node, resolved: List<Symbol>): Boolean {
        val id = (node as NodeBase).nodeId
        if (resolved.isEmpty() || id !in symbols.indices || symbols[id] != null) return false
        symbols[id] = if (resolved.size == 1) resolved[0] else resolved
        symbolsRecorded++
        return true
    }

    /**
     * (INV.2) The symbol(s) the name [node] resolved to, or empty when nothing was
     * recorded there — the same three readings as [typeAt]'s null.
     */
    fun symbolsAt(node: Node): List<Symbol> {
        val id = (node as NodeBase).nodeId
        if (id !in symbols.indices) return emptyList()
        return when (val slot = symbols[id]) {
            null -> emptyList()
            is Symbol -> listOf(slot)
            else -> {
                @Suppress("UNCHECKED_CAST")
                slot as List<Symbol>
            }
        }
    }

    /**
     * (INV.2) Records what overload resolution picked at the call-like [node],
     * unless an answer is already there. Returns whether the write happened.
     */
    fun recordCall(node: Node, call: ResolvedCall): Boolean {
        val id = (node as NodeBase).nodeId
        if (id !in types.indices || calls[id] != null) return false
        calls[id] = call
        callsRecorded++
        return true
    }

    /** (INV.2) The resolved call at [node], or null when nothing was recorded there. */
    fun callAt(node: Node): ResolvedCall? {
        val id = (node as NodeBase).nodeId
        return if (id in types.indices) calls[id] else null
    }

    /**
     * (INV.2) Records the type that contextually types [node], unless one is
     * already there. Returns whether the write happened.
     */
    fun recordContextual(node: Node, type: Type): Boolean {
        val id = (node as NodeBase).nodeId
        if (id !in contextual.indices || contextual[id] != null) return false
        contextual[id] = type
        contextualRecorded++
        return true
    }

    /** (INV.2) The contextual type recorded at [node], or null. */
    fun contextualAt(node: Node): Type? {
        val id = (node as NodeBase).nodeId
        return if (id in contextual.indices) contextual[id] else null
    }
}
