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

package com.xemantic.typescript.tsgo

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.tsgo.go.sync.WaitGroup
import com.xemantic.typescript.tsgo.go.sync.goSpawn
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.test.Test

/**
 * A throwable escaping a goroutine is fatal to the process (exit status 2, as Go's unrecovered panic), never a
 * silently dead thread: the GraalVM image of the CLI hung at 0% CPU on type-fest when an out-of-memory error was
 * raised again inside WaitGroup's own handler and the waiter was never signalled ((TSGO.6)).
 */
class GoroutineFatalTest {

    @Test
    fun `a throwable escaping a goroutine ends the process with status 2 instead of hanging its waiter`() {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val outFile = File.createTempFile("goroutine-fatal", ".out")
        val errFile = File.createTempFile("goroutine-fatal", ".err")
        val p = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "com.xemantic.typescript.tsgo.GoroutineFatalTestKt")
            .redirectOutput(outFile).redirectError(errFile).start()
        // The child waits 120 s for a goroutine that never signals; only the fatal exit ends it sooner.
        val finished = p.waitFor(60, TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly()
        assert(finished)
        val exit = p.exitValue()
        val stderr = errFile.readText()
        assert(exit == 2)
        assert(stderr == "fatal error: goroutine: java.lang.OutOfMemoryError: probe\n")
    }

    @Test
    fun `negative control - a throwable caught by WaitGroup go still reaches wait and does not end the process`() {
        val wg = WaitGroup()
        wg.go { throw IllegalStateException("probe") }
        val caught = runCatching { wg.wait() }.exceptionOrNull()
        assert(caught is IllegalStateException && caught.message == "probe")
    }
}

/** The child process of [GoroutineFatalTest]: a goroutine whose throwable escapes, and a waiter it never signals. */
fun main() {
    goSpawn { throw OutOfMemoryError("probe") }
    Thread.sleep(120_000)
    exitProcess(0)
}
