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
     * Counters, for the CLI report line. Added to ONCE per checker after its
     * spine walk, on the caller's thread; cleared by [reset] after the report
     * prints, exactly as [PassTiming]'s counters are. Not owned by the ledger,
     * for the reason its KDoc gives.
     */
    var recordedTotal: Long = 0
    var filesTotal: Int = 0

    fun reset() {
        recordedTotal = 0
        filesTotal = 0
    }
}

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

    /** How many nodes hold an answer — the receipt a cost measurement quotes. */
    var recorded: Int = 0
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
}
