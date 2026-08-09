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

package com.xemantic.typescript.compiler.server

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.protocol.CompileResponse
import com.xemantic.typescript.compiler.protocol.XTSC_PROTOCOL_VERSION
import com.xemantic.typescript.compiler.protocol.XTSC_REFUSED
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test

/**
 * (WARM.20) The `--daemon` dispatcher must ADOPT the daemon's exit code.
 *
 * ## The defect this pins, and why the existing pin could not see it
 *
 * `ExitCodeParityTest` asserts that the CLI and the daemon agree — on the code
 * the server puts **in the response**. That was always right. What
 * [runAsClient] then did with it was propagate `XTSC_REFUSED` and drop every
 * other non-zero code, so measured on 2026-08-09:
 *
 * | path | project with 1 error | exit |
 * |---|---|---|
 * | one-shot CLI | yes | **1** |
 * | `--daemon`, no server (in-process fallback) | yes | **1** |
 * | `--daemon`, served by a daemon | yes | **0** |
 *
 * `xtsc`'s answer therefore depended on whether a daemon happened to be
 * running — the exact property the 2026-08-08 exit-code change set out to end —
 * and CI branching on it would have read a failing compile as a pass. The
 * failure is silent by construction: the diagnostics are printed either way, so
 * only the code differs, and nothing prints it.
 *
 * ## Why the assertions look like this
 *
 * The response is INJECTED rather than served over a socket. This module's suite
 * never binds one on purpose (see `CompileServerTest`), which is precisely how a
 * bug on the response-to-exit-code edge survived — so the pin has to sit on that
 * edge and nowhere else. The arbitrary code 7 is there to pin PROPAGATION: a
 * fix that special-cased 1 alongside 2 would pass every other case here.
 */
class XtscClientExitCodeTest {

    private fun response(code: Int, output: String = "") = CompileResponse(
        output = output,
        exitCode = code,
        elapsedMs = 1,
        protocolVersion = XTSC_PROTOCOL_VERSION,
    )

    /** Runs the dispatcher with stdout captured, and returns (code, stdout). */
    private fun dispatch(
        args: Array<String>,
        served: CompileResponse?,
    ): Pair<Int, String> {
        val captured = ByteArrayOutputStream()
        val previous = System.out
        val code = try {
            System.setOut(PrintStream(captured, true, StandardCharsets.UTF_8))
            runAsClient(args, "/tmp/xtsc-pin.sock") { _, _ -> served }
        } finally {
            System.setOut(previous)
        }
        return code to captured.toString(StandardCharsets.UTF_8)
    }

    // THE case that was broken.
    @Test
    fun `a served compile that found errors exits one`() {
        val (code, _) = dispatch(arrayOf("--daemon", "--noEmit", "."), response(1))
        assert(code == 1)
    }

    @Test
    fun `a served clean compile exits zero`() {
        val (code, _) = dispatch(arrayOf("--daemon", "--noEmit", "."), response(0))
        assert(code == 0)
    }

    // Not a list of blessed codes - the response's code, whatever it is.
    @Test
    fun `an arbitrary daemon exit code is propagated rather than mapped`() {
        val (code, _) = dispatch(arrayOf("--daemon", "--noEmit", "."), response(7))
        assert(code == 7)
    }

    // The one code the old implementation did carry; it must keep working.
    @Test
    fun `a refused request still exits with the refusal code`() {
        val (code, _) = dispatch(arrayOf("--daemon", "--watch", "."), response(XTSC_REFUSED))
        assert(code == XTSC_REFUSED)
    }

    @Test
    fun `the daemon's output is reproduced verbatim on stdout`() {
        val (_, out) = dispatch(
            arrayOf("--daemon", "--noEmit", "."),
            response(1, "a.ts(1,7): error TS2322: nope\nFAILED — 1 error(s)\n"),
        )
        assert(out == "a.ts(1,7): error TS2322: nope\nFAILED — 1 error(s)\n")
    }

    // The other half of the parity: with nothing answering, the dispatcher
    // compiles here, and that path must return the same code the served one now
    // does. This is the arm that was already correct - it is in the pin so that
    // a future change cannot fix one and break the other unnoticed.
    @Test
    fun `the in-process fallback returns the compiler's own code`() {
        val dir = File.createTempFile("xtsc-fallback-", "").let { it.delete(); it.mkdirs(); it }
        File(dir, "tsconfig.json").writeText("""{ "compilerOptions": { "strict": true } }""")
        File(dir, "a.ts").writeText("export const n: number = 'not a number'\n")
        val (code, _) = dispatch(arrayOf("--daemon", "--noEmit", dir.absolutePath), null)
        assert(code == 1)
        dir.deleteRecursively()
    }

    @Test
    fun `the in-process fallback exits zero for a clean project`() {
        val dir = File.createTempFile("xtsc-fallback-ok-", "").let { it.delete(); it.mkdirs(); it }
        File(dir, "tsconfig.json").writeText("""{ "compilerOptions": { "strict": true } }""")
        File(dir, "a.ts").writeText("export const n: number = 1\n")
        val (code, _) = dispatch(arrayOf("--daemon", "--noEmit", dir.absolutePath), null)
        assert(code == 0)
        dir.deleteRecursively()
    }

}
