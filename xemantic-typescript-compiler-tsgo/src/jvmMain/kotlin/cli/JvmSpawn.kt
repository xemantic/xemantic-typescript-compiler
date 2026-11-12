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

package com.xemantic.typescript.tsgo.cli

import com.xemantic.typescript.tsgo.go.io.ReadWriteCloser
import com.xemantic.typescript.tsgo.go.io.Writer
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoPlainError
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.GoString
import com.xemantic.typescript.tsgo.runtime.Tuple2

/**
 * tsc's `System.Spawn` on the JVM (cmd/tsc/sys.go): starts [command] in [dir] and talks to it over its stdin/stdout
 * — TypeScript 7.1's content mappers are child processes speaking JSON-RPC there. The child's stderr is copied to
 * [stderr]. Reads block in `blockingWait` (a goroutine waiting on the child gives its run token back).
 */
fun jvmSpawn(command: GoSlice<String>, dir: String, stderr: Writer?): Tuple2<ReadWriteCloser?, GoError?> {
    val cmd = (0 until command.len).map { GoString.toUtf16(command[it]) }
    val process = try {
        ProcessBuilder(cmd).directory(java.io.File(GoString.toUtf16(dir))).start()
    } catch (e: java.io.IOException) {
        return Tuple2(null, GoPlainError("exec: ${e.message}"))
    }
    Thread({
        val buf = ByteArray(8192)
        val es = process.errorStream
        while (true) {
            val n = try { es.read(buf) } catch (_: java.io.IOException) { -1 }
            if (n < 0) break
            stderr?.write(GoSlice.of(com.xemantic.typescript.tsgo.runtime.GoElem.BYTE, *Array(n) { buf[it].toInt() and 0xFF }))
        }
    }, "xtsc-spawn-stderr").apply { isDaemon = true }.start()
    return Tuple2(ProcessConn(process), null)
}

private class ProcessConn(private val process: Process) : ReadWriteCloser {
    private val input = process.inputStream
    private val output = process.outputStream

    override fun read(p: GoSlice<Int>): Tuple2<Int, GoError?> {
        if (p.len == 0) return Tuple2(0, null)
        val buf = ByteArray(p.len)
        val n = try {
            com.xemantic.typescript.tsgo.go.sync.blockingWait { input.read(buf) }
        } catch (e: java.io.IOException) {
            return Tuple2(0, GoPlainError("read: ${e.message}"))
        }
        if (n < 0) return Tuple2(0, com.xemantic.typescript.tsgo.go.io.EOF)
        for (i in 0 until n) p[i] = buf[i].toInt() and 0xFF
        return Tuple2(n, null)
    }

    override fun write(p: GoSlice<Int>): Tuple2<Int, GoError?> = try {
        output.write(ByteArray(p.len) { p[it].toByte() })
        output.flush()
        Tuple2(p.len, null)
    } catch (e: java.io.IOException) {
        Tuple2(0, GoPlainError("write: ${e.message}"))
    }

    override fun close(): GoError? {
        try { output.close() } catch (_: java.io.IOException) {}
        process.destroy()
        return null
    }
}
