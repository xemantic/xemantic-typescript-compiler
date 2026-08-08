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

import kotlin.reflect.KMutableProperty0

/**
 * (SERVE.1): a save-and-restore ledger for the process-global DEBUG MODES the
 * CLI's argument loop turns on.
 *
 * ## Why this exists at all
 *
 * Every probe/arm switch in this compiler is a field on a process-global
 * `object` — `FlowScan.legacy`, `ArgNarrowGate.mode`, `ParallelCheckMode.workers`
 * and ~30 more. That is exactly right for a one-shot CLI, where the process
 * exits with the answer. It is wrong under `--serve`: `CompileServer` calls the
 * ordinary [runCli] once per request inside ONE long-lived JVM, so a flag the
 * argument loop sets and never clears silently reconfigures **every later
 * request on that server**, with nothing in that request's output to say so.
 * Several of these flags select a different code path (`--flowScanLegacy`,
 * `--argNarrowGateOff`, `--dispatchGated`, `--workers N`), so the leak is a
 * wrong-answer hazard and not merely a performance one.
 *
 * ## Why a ledger rather than a list of restores
 *
 * The obvious fix — a block at the end of `runCli` that assigns each flag its
 * default back — is wrong twice over. It is wrong in PRINCIPLE, because a mode
 * whose default is not the "off" value ([ArgNarrowGate.mode] defaults to
 * `ON`, [PassTiming.detail] to `true`) must be restored to *the value it had*,
 * never to a guessed default; round 619 lost a whole session to precisely that
 * mistake, where a test "restoring" `PassTiming.disabledPasses = emptySet()`
 * re-enabled the pass lab's disables for every alphabetically-later test class
 * and manufactured a false green across the generated corpus. And it is wrong
 * in PRACTICE, because it is a second list that has to be kept in step with the
 * first by hand — which is how six flags came to be missing from it.
 *
 * A ledger has neither problem: [set] records the value the field HELD, so a
 * restore is exact by construction, and there is no second list to forget,
 * because the save and the write are the same statement.
 *
 * ## The obligation this creates
 *
 * **Every write to a process-global mode field inside the CLI argument loop
 * goes through [set].** A bare `Obj.field = value` there compiles perfectly and
 * leaks; nothing in the type system can see it. `CliModeRestoreTest` is what
 * sees it — it drives the parser with every flag the usage text documents and
 * asserts, by JVM reflection over the mode objects' declared fields, that the
 * restore puts every single one back.
 *
 * Counters are deliberately NOT in scope: they are cleared by each object's own
 * `reset()`, they change during any compile whether a probe asked or not, and
 * restoring them would erase the very numbers a request was run to produce.
 */
internal class ModeLedger {

    private val undo = ArrayList<() -> Unit>()

    /**
     * Writes [value] to [property], remembering what it held so [restore] can
     * put it back.
     *
     * Bound property references (`FlowScan::legacy`) are used rather than
     * getter/setter lambda pairs because the pair form lets a call site read one
     * field and write another — a mistake that type-checks and that no gate
     * could see, the same hazard class as a positional argument permutation.
     */
    fun <T> set(property: KMutableProperty0<T>, value: T) {
        val old = property.get()
        undo.add { property.set(old) }
        property.set(value)
    }

    /**
     * Restores every field [set] touched, in REVERSE order.
     *
     * The order is load-bearing whenever one argument implies another —
     * `--verifyLoopRetryAll` sets `verifyLoopRetry` after `--verifyLoopRetry`
     * already did, and `--passTimingRows` writes two fields a later
     * `--verifyMappedCache` also touches. Undoing last-to-first lands on the
     * value the field held before the FIRST write, which is the request's true
     * entry state; forward order would land on whatever an intermediate arm
     * saved.
     */
    fun restore() {
        for (k in undo.indices.reversed()) undo[k]()
        undo.clear()
    }

    /** How many writes are pending an undo — the non-vacuity check for a pin. */
    val pending: Int get() = undo.size
}
