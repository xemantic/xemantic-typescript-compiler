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

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.protocol.CompileRequest
import com.xemantic.typescript.compiler.server.CompileServer
import java.io.File
import kotlin.test.Test

/**
 * (SERVE.1): the BEHAVIOUR-CHANGING modes a request turns on must be off again
 * when that request returns — checked end to end through the real server entry
 * point.
 *
 * ## The mistake this catches
 *
 * `CompileServerPassTimingTest` (round 843) covers the instrumentation half:
 * `PassTiming`, `FltmCensus`, `GlobalsAmp`. This covers the half that is a
 * CORRECTNESS hazard rather than a performance one. Each mode below selects a
 * different code path — the pre-801 flow scanner, the pre-796 argument-narrowing
 * gate, the pre-798/799/800 implicit-any arms, the derived-dispatch-table-only
 * walk, and the parallel share-nothing partition driver (which was an outright
 * race until round 825). Under `--serve` all of them used to survive their
 * request: one `xtsc --workers 4` and every later compile on that daemon ran
 * parallel, forever, with nothing in any output to say so.
 *
 * ## Why these assertions DISCRIMINATE
 *
 * They read the flag OBJECTS, not the response text, because for all but one of
 * these modes the response is identical either way — that is the whole reason
 * the leak went unnoticed. The one exception is [PartitionCheck.reportLines],
 * an accumulating list rather than a mode: leaked, its lines are re-printed by
 * every later request, so that one IS visible in the text and is asserted there.
 *
 * The first request's assertions are the non-vacuity check: the modes must
 * actually have been on during it, or "off afterwards" is satisfied by a request
 * that never turned them on.
 *
 * jvmTest rather than commonTest because [CompileServer] is JVM-only.
 */
class CompileServerModeLeakTest {

    private fun tinyProject(): File {
        val dir = File.createTempFile("xtsc-modeleak-", "").let {
            it.delete(); it.mkdirs(); it
        }
        File(dir, "tsconfig.json").writeText("""{ "compilerOptions": { "strict": true } }""")
        File(dir, "a.ts").writeText("export const n: number = 1\n")
        return dir
    }

    @Test
    fun `a request that selects behaviour-changing arms leaves none of them selected`() {
        val dir = tinyProject()
        // Round 619's rule: capture what the fields HELD, so the cleanup below
        // cannot itself install a guessed default. `ArgNarrowGate.mode` defaults
        // to ON, not to its zero value, which is exactly the trap.
        val saved = listOf(
            ParallelCheckMode.workers, ArgNarrowGate.mode, SpineDispatch.mode,
        )
        val savedFlags = listOf(
            FlowScan.legacy, FlowScan.eagerSet, IanySections.gateOff,
            IanySections.armGateOff, IanySections.argGateOff,
            CpaSections.verifyLoopRetry, CpaSections.verifyUnionRetry,
        )
        try {
            val armed = CompileServer.respondTo(
                CompileRequest(
                    listOf(
                        "--noEmit",
                        "--workers", "2",
                        "--flowScanLegacy",
                        "--flowEagerSet",
                        "--argNarrowGateOff",
                        "--ianyGateOff", "--ianyArmGateOff", "--ianyArgGateOff",
                        "--verifyLoopRetry", "--verifyUnionRetry",
                        "--dispatchGated",
                        dir.absolutePath,
                    ),
                ),
            )
            // Non-vacuity: the armed request really ran, on the arms it asked for.
            assert(armed.exitCode == 0)
            assert("OK — 0 errors" in armed.output)

            // THE pin: every one of them is back at the value it held.
            assert(ParallelCheckMode.workers == saved[0])
            assert(ArgNarrowGate.mode == saved[1])
            assert(SpineDispatch.mode == saved[2])
            assert(FlowScan.legacy == savedFlags[0])
            assert(FlowScan.eagerSet == savedFlags[1])
            assert(IanySections.gateOff == savedFlags[2])
            assert(IanySections.armGateOff == savedFlags[3])
            assert(IanySections.argGateOff == savedFlags[4])
            assert(CpaSections.verifyLoopRetry == savedFlags[5])
            assert(CpaSections.verifyUnionRetry == savedFlags[6])

            val plain = CompileServer.respondTo(
                CompileRequest(listOf("--noEmit", dir.absolutePath)),
            )
            assert(plain.exitCode == 0)
            // ... and a second request cannot re-arm them either.
            assert(ParallelCheckMode.workers == saved[0])
            assert(ArgNarrowGate.mode == saved[1])
            assert(!FlowScan.legacy)
        } finally {
            ParallelCheckMode.workers = saved[0]
            ArgNarrowGate.mode = saved[1]
            SpineDispatch.mode = saved[2]
            FlowScan.legacy = savedFlags[0]
            FlowScan.eagerSet = savedFlags[1]
            IanySections.gateOff = savedFlags[2]
            IanySections.armGateOff = savedFlags[3]
            IanySections.argGateOff = savedFlags[4]
            CpaSections.verifyLoopRetry = savedFlags[5]
            CpaSections.verifyUnionRetry = savedFlags[6]
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a partition-check request does not print its report again on the next request`() {
        val dir = tinyProject()
        val savedWorkers = PartitionCheck.workers
        try {
            val checked = CompileServer.respondTo(
                CompileRequest(listOf("--noEmit", "--partitionCheck", "2", dir.absolutePath)),
            )
            assert(checked.exitCode == 0)
            // Non-vacuity: the partition harness really ran and really reported.
            assert("partition" in checked.output.lowercase())

            val plain = CompileServer.respondTo(
                CompileRequest(listOf("--noEmit", dir.absolutePath)),
            )
            assert(plain.exitCode == 0)
            // The mode is a field the ledger restores ...
            assert(PartitionCheck.workers == savedWorkers)
            // ... and the report is an accumulating list, which is why this leak
            // is the one that shows up in the response TEXT.
            assert("partition" !in plain.output.lowercase())
            assert(PartitionCheck.reportLines.isEmpty())
        } finally {
            PartitionCheck.workers = savedWorkers
            PartitionCheck.reportLines.clear()
            dir.deleteRecursively()
        }
    }
}
