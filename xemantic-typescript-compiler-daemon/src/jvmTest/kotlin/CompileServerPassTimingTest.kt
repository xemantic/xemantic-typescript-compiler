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
import com.xemantic.typescript.compiler.server.CompileServer
import java.io.File
import kotlin.test.Test

/**
 * (SERVER.1): the instrumentation flags a `--passTiming` request turns ON must
 * be OFF again when that request returns.
 *
 * ## The mistake this catches
 *
 * `main`'s pass-timing block used to do `PassTiming.reset(); PassTiming.enabled
 * = true`, compile, dump — and never clear `enabled` (nor `verifyMappedCache`,
 * `callerAttr`, `FltmCensus.on`, `GlobalsAmp.reads`, which `--verifyMappedCache`
 * / `--typeOfExprCallers` / `--fltmCensus` / `--globalsAmp` set through the same
 * block). That was harmless while `main` ran once per process. It is not
 * harmless now: [CompileServer] calls the ordinary `main` once per request
 * inside ONE long-lived JVM, so a single `--daemon --passTiming` request left
 * every LATER request on that server instrumented — permanently, invisibly, and
 * with no second dump to show it. The probe is not free (round 733 measured it
 * alone moving `checkSpine` by +29 ms; `--globalsAmp` re-reads every globals
 * lookup N times), so the leak is a silent tax on exactly the warm path the
 * server exists for.
 *
 * ## Why these assertions DISCRIMINATE
 *
 * Against the pre-fix code the second request's `PassTiming.passNanos` is
 * NON-empty — it recorded ~500 passes it was never asked to record — so
 * `assert(PassTiming.passNanos.isEmpty())` fails there, and so does
 * `assert(!PassTiming.enabled)`. Note what would NOT discriminate: the second
 * response's OUTPUT is table-free either way, because the dump is gated on the
 * request's own `--passTiming` argument rather than on the flag. The leak is
 * observable only in the flag and in the work the next compile then does, which
 * is why this pin reads the object rather than the text.
 *
 * The first request's assertions are the pin's own non-vacuity check: the table
 * must actually have been printed, or "the flag is off afterwards" would be
 * satisfied by a run that never turned it on.
 *
 * jvmTest rather than commonTest because [CompileServer] is JVM-only.
 */
class CompileServerPassTimingTest {

    private val tableHeader = "== xtsc pass timing (INV.0) =="

    private fun tinyProject(): File {
        val dir = File.createTempFile("xtsc-passtiming-", "").let {
            it.delete(); it.mkdirs(); it
        }
        File(dir, "tsconfig.json").writeText("""{ "compilerOptions": { "strict": true } }""")
        File(dir, "a.ts").writeText("export const n: number = 1\n")
        return dir
    }

    @Test
    fun `a --passTiming request dumps its table and leaves the next request uninstrumented`() {
        val savedEnabled = PassTiming.enabled
        val dir = tinyProject()
        try {
            val instrumented = CompileServer.respondTo(
                clientRequest(
                    listOf("--noEmit", "--passTiming", dir.absolutePath),
                ),
            )
            // Non-vacuity: the flag really was on for this request.
            assert(tableHeader in instrumented.output)
            assert(instrumented.exitCode == 0)
            // ... and off again the moment it returned.
            assert(!PassTiming.enabled)
            assert(PassTiming.passNanos.isEmpty())

            val plain = CompileServer.respondTo(
                clientRequest(listOf("--noEmit", dir.absolutePath)),
            )
            assert(plain.exitCode == 0)
            assert(tableHeader !in plain.output)
            // THE pin: a request that asked for no instrumentation did none.
            assert(!PassTiming.enabled)
            assert(PassTiming.passNanos.isEmpty())
            assert(PassTiming.getTypeOfExpressionCalls == 0L)
        } finally {
            PassTiming.enabled = savedEnabled
            PassTiming.reset()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the modes that imply --passTiming are restored too`() {
        val savedEnabled = PassTiming.enabled
        val dir = tinyProject()
        try {
            // Both of these set `passTiming = true` at the parse site AND their
            // own mode field; only the first was ever cleared before the fix.
            val response = CompileServer.respondTo(
                clientRequest(
                    listOf(
                        "--noEmit", "--verifyMappedCache", "--globalsAmp", "3",
                        dir.absolutePath,
                    ),
                ),
            )
            assert(tableHeader in response.output)
            assert(response.exitCode == 0)
            assert(!PassTiming.enabled)
            assert(!PassTiming.verifyMappedCache)
            assert(!PassTiming.callerAttr)
            assert(!FltmCensus.on)
            assert(GlobalsAmp.reads == 0)
        } finally {
            PassTiming.enabled = savedEnabled
            PassTiming.verifyMappedCache = false
            PassTiming.callerAttr = false
            FltmCensus.on = false
            GlobalsAmp.reads = 0
            PassTiming.reset()
            dir.deleteRecursively()
        }
    }
}
